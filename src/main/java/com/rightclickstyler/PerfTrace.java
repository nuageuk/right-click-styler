package com.rightclickstyler;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;

/**
 * Opt-in performance measurement ({@code -Drightclickstyler.perfTrace=true}), independent of the
 * submenu prototype so the same scene can be measured with it on and off. Client thread only,
 * under the overlay's lock. Diagnostic: nothing here affects what is drawn.
 *
 * <p>Frame time is the interval between consecutive overlay renders, which RuneLite runs once per
 * client frame, attributed to what the later frame showed. Allocation is the client thread's own
 * allocated-bytes counter, so it includes RuneLite's work as well as ours.
 */
final class PerfTrace
{
	static final boolean DEFAULT = Boolean.getBoolean("rightclickstyler.perfTrace");
	private static final long WINDOW_NANOS = 5_000_000_000L;
	private static final long MAX_INTERVAL_NANOS = 1_000_000_000L;
	private static final int CAPACITY = 16_384;

	/** What a client frame showed. */
	enum Frame
	{
		MENU_CLOSED, MENU_NATIVE, PLAIN_THEMED, SUBMENU_THEMED
	}

	/** Per-call costs in nanoseconds, except the byte counts. */
	enum Cost
	{
		PREPARE_ROOT_ONLY, PREPARE_WITH_CHILD, PREPARE_FALLBACK, PROBE, THEME_RENDER, THEME_CACHE_HIT,
		BLIT, PLAIN_RENDER, PREPARE_ALLOCATED_BYTES, PLAIN_ALLOCATED_BYTES
	}

	/** Probe passes by visible candidate count, matching the frame classes of the A/B runs. */
	enum ProbeClass
	{
		ROOT_ONLY, LIGHT_1_4, CHILD_5_19, HEAVY_20_PLUS;

		static ProbeClass of(int visibleCandidates)
		{
			return visibleCandidates <= 0 ? ROOT_ONLY : visibleCandidates < 5 ? LIGHT_1_4
				: visibleCandidates < 20 ? CHILD_5_19 : HEAVY_20_PLUS;
		}
	}

	/** Probe work counters worth a distribution; the rest are in the per-allocation and slow logs. */
	private static final SubmenuPrototypeProbe.Counter[] PROBE_COUNTERS = {
		SubmenuPrototypeProbe.Counter.VISIBLE_CANDIDATES, SubmenuPrototypeProbe.Counter.ROOT_PIXELS,
		SubmenuPrototypeProbe.Counter.COVERAGE_WRITES, SubmenuPrototypeProbe.Counter.WITNESS_SCANNED,
		SubmenuPrototypeProbe.Counter.EXCLUSIVE_MARKS, SubmenuPrototypeProbe.Counter.SHARED_MARKS,
		SubmenuPrototypeProbe.Counter.FOOTPRINT_SCANNED, SubmenuPrototypeProbe.Counter.FOOTPRINT_MARKS,
		SubmenuPrototypeProbe.Counter.EXACT_CHANGED, SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES,
	};
	private static final int PROBE_CAPACITY = 2_048;
	private static final int MAX_ALLOCATION_EVENTS = 8;

	static final class Samples
	{
		private final long[] values;
		private int count;
		private long overflow;

		Samples()
		{
			this(CAPACITY);
		}

		Samples(int capacity)
		{
			values = new long[capacity];
		}

		void add(long value)
		{
			if (count == values.length)
			{
				overflow++;
				return;
			}
			values[count++] = value;
		}

		int count()
		{
			return count;
		}

		/** p50, p90, p99, max; empty when no samples. */
		long[] percentiles()
		{
			if (count == 0)
			{
				return new long[0];
			}
			long[] sorted = Arrays.copyOf(values, count);
			Arrays.sort(sorted);
			return new long[]{at(sorted, 0.50), at(sorted, 0.90), at(sorted, 0.99), sorted[count - 1]};
		}

		private static long at(long[] sorted, double p)
		{
			return sorted[Math.min(sorted.length - 1, (int) (p * sorted.length))];
		}

		long sum()
		{
			long total = 0;
			for (int i = 0; i < count; i++)
			{
				total += values[i];
			}
			return total;
		}

		void clear()
		{
			count = 0;
			overflow = 0;
		}
	}

	private final Samples[] intervals = new Samples[Frame.values().length];
	private final Samples[] fps = new Samples[Frame.values().length];
	private final Samples[] costs = new Samples[Cost.values().length];
	private final Samples[] probeTotals = new Samples[ProbeClass.values().length];
	private final Samples[][] probePhases = new Samples[ProbeClass.values().length][SubmenuPrototypeProbe.Phase.values().length];
	private final Samples[][] probeCounters = new Samples[ProbeClass.values().length][PROBE_COUNTERS.length];
	private final List<String> allocationEvents = new java.util.ArrayList<>();
	private int droppedAllocationEvents;
	private final com.sun.management.ThreadMXBean allocation;
	private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
	private long lastFrameNanos;
	private long windowStart;
	private long windowAllocated = -1;
	private long gcCount;
	private long gcMillis;

	PerfTrace()
	{
		for (int i = 0; i < intervals.length; i++)
		{
			intervals[i] = new Samples();
			fps[i] = new Samples();
		}
		for (int i = 0; i < costs.length; i++)
		{
			costs[i] = new Samples();
		}
		for (int c = 0; c < probeTotals.length; c++)
		{
			probeTotals[c] = new Samples(PROBE_CAPACITY);
			for (int p = 0; p < probePhases[c].length; p++)
			{
				probePhases[c][p] = new Samples(PROBE_CAPACITY);
			}
			for (int k = 0; k < probeCounters[c].length; k++)
			{
				probeCounters[c][k] = new Samples(PROBE_CAPACITY);
			}
		}
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		com.sun.management.ThreadMXBean sun = threads instanceof com.sun.management.ThreadMXBean
			? (com.sun.management.ThreadMXBean) threads : null;
		allocation = sun != null && sun.isThreadAllocatedMemorySupported() && sun.isThreadAllocatedMemoryEnabled()
			? sun : null;
	}

	/** Bytes allocated so far by the calling thread, or -1 when the JVM cannot say. */
	long allocatedBytes()
	{
		return allocation == null ? -1 : allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
	}

	void frame(Frame kind, int clientFps, long now)
	{
		if (lastFrameNanos != 0 && now - lastFrameNanos < MAX_INTERVAL_NANOS)
		{
			intervals[kind.ordinal()].add(now - lastFrameNanos);
			fps[kind.ordinal()].add(clientFps);
		}
		lastFrameNanos = now;
	}

	void cost(Cost cost, long value)
	{
		if (value >= 0)
		{
			costs[cost.ordinal()].add(value);
		}
	}

	Samples samples(Cost cost)
	{
		return costs[cost.ordinal()];
	}

	/** One probe pass (each pass of a geometry re-sample counts separately). */
	void probe(SubmenuPrototypeProbe.Result result)
	{
		if (result == null || result.passNanos <= 0)
		{
			return;
		}
		int c = ProbeClass.of(result.visibleCandidates).ordinal();
		probeTotals[c].add(result.passNanos);
		for (int p = 0; p < probePhases[c].length; p++)
		{
			probePhases[c][p].add(result.phaseNanos[p]);
		}
		for (int k = 0; k < PROBE_COUNTERS.length; k++)
		{
			probeCounters[c][k].add(result.count(PROBE_COUNTERS[k]));
		}
		if (result.count(SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES) > 0)
		{
			if (allocationEvents.size() < MAX_ALLOCATION_EVENTS)
			{
				allocationEvents.add(String.format("{bytes=%d allocations=%d allocUs=%d passUs=%d frame=%d visible=%d candidates=%d}",
					result.count(SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES),
					result.count(SubmenuPrototypeProbe.Counter.ALLOCATIONS),
					result.phase(SubmenuPrototypeProbe.Phase.ALLOCATE) / 1_000, result.passNanos / 1_000,
					result.count(SubmenuPrototypeProbe.Counter.FRAME_PIXELS), result.visibleCandidates,
					result.count(SubmenuPrototypeProbe.Counter.CANDIDATES)));
			}
			else
			{
				droppedAllocationEvents++;
			}
		}
	}

	Samples probeTotals(ProbeClass probeClass)
	{
		return probeTotals[probeClass.ordinal()];
	}

	Samples probePhase(ProbeClass probeClass, SubmenuPrototypeProbe.Phase phase)
	{
		return probePhases[probeClass.ordinal()][phase.ordinal()];
	}

	/**
	 * One line per probe class: each phase's share of the summed pass time (so the shares are of
	 * measured client-thread probe time, not estimates), then per-phase p50/p90/max and work.
	 */
	private void reportProbes(Logger log, double seconds)
	{
		for (ProbeClass probeClass : ProbeClass.values())
		{
			Samples totals = probeTotals[probeClass.ordinal()];
			if (totals.count() == 0)
			{
				continue;
			}
			long[] t = totals.percentiles();
			double sum = Math.max(1, totals.sum());
			StringBuilder share = new StringBuilder();
			StringBuilder phases = new StringBuilder();
			for (SubmenuPrototypeProbe.Phase phase : SubmenuPrototypeProbe.Phase.values())
			{
				Samples samples = probePhases[probeClass.ordinal()][phase.ordinal()];
				long phaseSum = samples.sum();
				if (phaseSum == 0)
				{
					continue;
				}
				long[] p = samples.percentiles();
				share.append(String.format(" %s=%.1f%%", phase, 100.0 * phaseSum / sum));
				phases.append(String.format(" %s=%.0f/%.0f/%.0f", phase, p[0] / 1e3, p[1] / 1e3, p[3] / 1e3));
			}
			StringBuilder work = new StringBuilder();
			for (int k = 0; k < PROBE_COUNTERS.length; k++)
			{
				Samples samples = probeCounters[probeClass.ordinal()][k];
				long[] p = samples.percentiles();
				if (p[3] != 0)
				{
					work.append(String.format(" %s=%d/%d", PROBE_COUNTERS[k], p[0], p[3]));
				}
			}
			log.info("Submenu probe phases window={}s class={} n={} passUs p50={} p90={} p99={} max={} sumMs={} | share{} | p50/p90/maxUs{} | work p50/max{}",
				String.format("%.1f", seconds), probeClass, totals.count(),
				t[0] / 1_000, t[1] / 1_000, t[2] / 1_000, t[3] / 1_000, String.format("%.1f", sum / 1e6),
				share, phases, work);
		}
		if (!allocationEvents.isEmpty())
		{
			log.info("Submenu probe allocations window={}s events={}{}", String.format("%.1f", seconds),
				String.join(" ", allocationEvents),
				droppedAllocationEvents == 0 ? "" : " dropped=" + droppedAllocationEvents);
		}
	}

	Samples intervals(Frame frame)
	{
		return intervals[frame.ordinal()];
	}

	/** Logs and resets one window at most every 5s. Call on the client thread. */
	void report(Logger log, String extra, long now)
	{
		long allocated = allocatedBytes();
		long count = 0;
		long millis = 0;
		for (GarbageCollectorMXBean collector : collectors)
		{
			count += Math.max(0, collector.getCollectionCount());
			millis += Math.max(0, collector.getCollectionTime());
		}
		if (windowStart == 0)
		{
			start(now, allocated, count, millis);
			return;
		}
		if (now - windowStart < WINDOW_NANOS)
		{
			return;
		}
		double seconds = (now - windowStart) / 1e9;
		StringBuilder frames = new StringBuilder();
		for (Frame frame : Frame.values())
		{
			Samples interval = intervals[frame.ordinal()];
			if (interval.count() == 0)
			{
				continue;
			}
			long[] p = interval.percentiles();
			long[] f = fps[frame.ordinal()].percentiles();
			frames.append(String.format(" %s{n=%d intervalMs p50=%.2f p90=%.2f p99=%.2f max=%.2f fps=%.0f clientFps p50=%d min=%d}",
				frame, interval.count(), p[0] / 1e6, p[1] / 1e6, p[2] / 1e6, p[3] / 1e6,
				interval.count() * 1e9 / Math.max(1, interval.sum()), f[0], sortedMin(fps[frame.ordinal()])));
		}
		StringBuilder work = new StringBuilder();
		for (Cost cost : Cost.values())
		{
			Samples samples = costs[cost.ordinal()];
			if (samples.count() == 0)
			{
				continue;
			}
			long[] p = samples.percentiles();
			boolean bytes = cost.name().endsWith("BYTES");
			double scale = bytes ? 1 : 1e3; // bytes, or microseconds
			work.append(String.format(" %s{n=%d %s p50=%.0f p90=%.0f p99=%.0f max=%.0f}",
				cost, samples.count(), bytes ? "bytes" : "us", p[0] / scale, p[1] / scale, p[2] / scale, p[3] / scale));
		}
		log.info("Submenu perf window={}s{} |{} | clientThreadAllocMBps={} gcCount={} gcMs={}{}",
			String.format("%.1f", seconds), frames, work,
			allocated < 0 || windowAllocated < 0 ? "n/a" : String.format("%.1f", (allocated - windowAllocated) / seconds / 1e6),
			count - gcCount, millis - gcMillis, extra == null ? "" : " | " + extra);
		reportProbes(log, seconds);
		for (int c = 0; c < probeTotals.length; c++)
		{
			probeTotals[c].clear();
			for (Samples samples : probePhases[c])
			{
				samples.clear();
			}
			for (Samples samples : probeCounters[c])
			{
				samples.clear();
			}
		}
		allocationEvents.clear();
		droppedAllocationEvents = 0;
		for (Samples samples : intervals)
		{
			samples.clear();
		}
		for (Samples samples : fps)
		{
			samples.clear();
		}
		for (Samples samples : costs)
		{
			samples.clear();
		}
		start(now, allocated, count, millis);
	}

	private void start(long now, long allocated, long count, long millis)
	{
		windowStart = now;
		windowAllocated = allocated;
		gcCount = count;
		gcMillis = millis;
	}

	private static long sortedMin(Samples samples)
	{
		long min = Long.MAX_VALUE;
		for (int i = 0; i < samples.count; i++)
		{
			min = Math.min(min, samples.values[i]);
		}
		return samples.count == 0 ? 0 : min;
	}
}
