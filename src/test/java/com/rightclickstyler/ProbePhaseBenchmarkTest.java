package com.rightclickstyler;

import java.awt.Rectangle;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assume.assumeTrue;

/**
 * Timing only, skipped unless {@code -Pbenchmark=true}. Measures the probe at one optimisation
 * level per JVM ({@code -PtestProbeOpt=0..4}), so the JIT profiles of one level never shape
 * another, on every logged GPU geometry (990x592 canvas), each drawn under its ground-truth
 * hypothesis: the one that reproduces the logged evidence counts exactly, as
 * {@link LoggedAmbiguousReplayTest} establishes. The native draw is a fill stand-in, so
 * NATIVE_DRAW is not representative; every other phase is the probe's own work.
 *
 * <ul>
 * <li>steady: one reused, JIT-warm instance per scene, as live while a menu stays open;</li>
 * <li>churn: the same instance cycling through all scenes, so every pass sees a new candidate
 * shape, which is where buffer growth and garbage happen;</li>
 * <li>cold: a fresh instance per pass with the JIT warm, the first pass after a canvas change.</li>
 * </ul>
 *
 * <p>Output lines start with PROBEBENCH: {@code gradlew test --tests '*ProbePhaseBenchmarkTest'
 * -Pbenchmark=true -PtestProbeOpt=N -i | grep PROBEBENCH}.
 */
public class ProbePhaseBenchmarkTest
{
	private static final int WARM_ROUNDS = 40;
	private static final int RUNS = 200;
	private static final int CHURN_ROUNDS = 40;
	private static final int COLD_RUNS = 20;
	private static final SubmenuPrototypeProbe.Counter[] WORK = {
		SubmenuPrototypeProbe.Counter.PIXELS_VISITED, SubmenuPrototypeProbe.Counter.SENTINEL_WRITES,
		SubmenuPrototypeProbe.Counter.WITNESS_SCANNED, SubmenuPrototypeProbe.Counter.SHARED_WRITES,
		SubmenuPrototypeProbe.Counter.FOOTPRINT_SCANNED, SubmenuPrototypeProbe.Counter.RESOLVER_POSITIONS,
		SubmenuPrototypeProbe.Counter.RESOLVER_SCANNED, SubmenuPrototypeProbe.Counter.RESOLVER_CLEARED,
	};

	@Test
	public void probePhaseCostOnLoggedGeometries() throws Exception
	{
		assumeTrue(Boolean.getBoolean("rightclickstyler.benchmark"));
		int level = SubmenuPrototypeProbe.DEFAULT_OPTIMISATION;
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long thread = Thread.currentThread().getId();
		List<Scene> scenes = new ArrayList<>();
		for (String[] record : LoggedAmbiguousReplayTest.records())
		{
			scenes.add(new Scene(record));
		}
		SubmenuPrototypeProbe warmProbe = ProbeSemanticsGoldenTest.probe(level);
		for (int round = 0; round < WARM_ROUNDS; round++)
		{
			for (Scene scene : scenes)
			{
				scene.run(warmProbe);
			}
		}

		SubmenuPrototypeProbe.Phase[] phases = SubmenuPrototypeProbe.Phase.values();
		PerfTrace.ProbeClass[] classes = PerfTrace.ProbeClass.values();
		long[][] pooled = new long[classes.length][0];
		double[][] phaseSum = new double[classes.length][phases.length];
		double[][] workSum = new double[classes.length][WORK.length];
		double[] bytesSum = new double[classes.length];
		int[] scenesIn = new int[classes.length];
		for (Scene scene : scenes)
		{
			SubmenuPrototypeProbe probe = ProbeSemanticsGoldenTest.probe(level);
			SubmenuPrototypeProbe.Result last = scene.run(probe);
			long[] totals = new long[RUNS];
			long[][] phaseRuns = new long[phases.length][RUNS];
			long allocatedBefore = threads.getThreadAllocatedBytes(thread);
			for (int i = 0; i < RUNS; i++)
			{
				last = scene.run(probe);
				totals[i] = last.passNanos;
				for (int p = 0; p < phases.length; p++)
				{
					phaseRuns[p][i] = last.phaseNanos[p];
				}
			}
			long bytesPerProbe = (threads.getThreadAllocatedBytes(thread) - allocatedBefore) / RUNS;
			int c = PerfTrace.ProbeClass.of(last.visibleCandidates).ordinal();
			scenesIn[c]++;
			int offset = pooled[c].length;
			pooled[c] = Arrays.copyOf(pooled[c], offset + RUNS);
			System.arraycopy(totals, 0, pooled[c], offset, RUNS);
			StringBuilder line = new StringBuilder();
			for (int p = 0; p < phases.length; p++)
			{
				Arrays.sort(phaseRuns[p]);
				long median = phaseRuns[p][RUNS / 2];
				phaseSum[c][p] += median;
				if (median >= 1_000)
				{
					line.append(' ').append(phases[p]).append('=').append(median / 1_000);
				}
			}
			for (int k = 0; k < WORK.length; k++)
			{
				workSum[c][k] += last.count(WORK[k]);
			}
			bytesSum[c] += bytesPerProbe;
			Arrays.sort(totals);
			System.out.printf("PROBEBENCH level=%d scene=%s class=%s visible=%d state=%s exact=%s passP50Us=%d |%s | bytesPerProbe=%d %s%n",
				level, scene.name, classes[c], last.visibleCandidates, last.state, last.exact.state,
				totals[RUNS / 2] / 1_000, line, bytesPerProbe, last.counterSummary());
		}
		for (int c = 0; c < classes.length; c++)
		{
			if (scenesIn[c] == 0)
			{
				continue;
			}
			long[] all = pooled[c];
			Arrays.sort(all);
			StringBuilder phaseMeans = new StringBuilder();
			for (int p = 0; p < phases.length; p++)
			{
				double mean = phaseSum[c][p] / scenesIn[c] / 1e3;
				if (mean >= 0.5)
				{
					phaseMeans.append(String.format(" %s=%.0f", phases[p], mean));
				}
			}
			StringBuilder work = new StringBuilder();
			for (int k = 0; k < WORK.length; k++)
			{
				work.append(String.format(" %s=%.0f", WORK[k], workSum[c][k] / scenesIn[c]));
			}
			System.out.printf("PROBEBENCH summary level=%d class=%s scenes=%d passUs p50=%d p90=%d p99=%d | meanPhaseUs%s | meanWork%s bytesPerProbe=%.0f%n",
				level, classes[c], scenesIn[c], all[all.length / 2] / 1_000, all[all.length * 9 / 10] / 1_000,
				all[all.length * 99 / 100] / 1_000, phaseMeans, work, bytesSum[c] / scenesIn[c]);
		}

		// Churn: one instance, a different candidate shape on every pass.
		System.gc();
		SubmenuPrototypeProbe churnProbe = ProbeSemanticsGoldenTest.probe(level);
		long gcCount = gcCount();
		long gcMillis = gcMillis();
		long allocatedBefore = threads.getThreadAllocatedBytes(thread);
		long[] churn = new long[CHURN_ROUNDS * scenes.size()];
		long grownBytes = 0;
		long growths = 0;
		long allocNanos = 0;
		int n = 0;
		for (int round = 0; round < CHURN_ROUNDS; round++)
		{
			for (Scene scene : scenes)
			{
				SubmenuPrototypeProbe.Result r = scene.run(churnProbe);
				churn[n++] = r.passNanos;
				grownBytes += r.count(SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES);
				growths += r.count(SubmenuPrototypeProbe.Counter.ALLOCATIONS);
				allocNanos += r.phase(SubmenuPrototypeProbe.Phase.ALLOCATE);
			}
		}
		long churnBytes = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
		long churnGcCount = gcCount() - gcCount;
		long churnGcMillis = gcMillis() - gcMillis;
		Arrays.sort(churn);
		System.out.printf("PROBEBENCH churn level=%d passes=%d passUs p50=%d p90=%d p99=%d max=%d | bufferGrowths=%d grownBytes=%d allocateUs=%d | threadBytesPerProbe=%d gcCount=%d gcMs=%d%n",
			level, churn.length, churn[churn.length / 2] / 1_000, churn[churn.length * 9 / 10] / 1_000,
			churn[churn.length * 99 / 100] / 1_000, churn[churn.length - 1] / 1_000,
			growths, grownBytes, allocNanos / 1_000, churnBytes / churn.length, churnGcCount, churnGcMillis);

		// Cold buffers, warm JIT: the first pass after a canvas change, on the largest scene.
		Scene heaviest = scenes.get(0);
		for (Scene scene : scenes)
		{
			if (scene.candidatePixels() > heaviest.candidatePixels())
			{
				heaviest = scene;
			}
		}
		long[] cold = new long[COLD_RUNS];
		long[] coldAlloc = new long[COLD_RUNS];
		SubmenuPrototypeProbe.Result coldResult = null;
		for (int i = 0; i < COLD_RUNS; i++)
		{
			coldResult = heaviest.run(ProbeSemanticsGoldenTest.probe(level));
			cold[i] = coldResult.passNanos;
			coldAlloc[i] = coldResult.phase(SubmenuPrototypeProbe.Phase.ALLOCATE);
		}
		Arrays.sort(cold);
		Arrays.sort(coldAlloc);
		System.out.printf("PROBEBENCH cold level=%d scene=%s visible=%d coldPassP50Us=%d coldAllocateP50Us=%d allocatedBytes=%d allocations=%d%n",
			level, heaviest.name, coldResult.visibleCandidates, cold[COLD_RUNS / 2] / 1_000, coldAlloc[COLD_RUNS / 2] / 1_000,
			coldResult.count(SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES),
			coldResult.count(SubmenuPrototypeProbe.Counter.ALLOCATIONS));
	}

	private static long gcCount()
	{
		long total = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
		{
			total += Math.max(0, gc.getCollectionCount());
		}
		return total;
	}

	private static long gcMillis()
	{
		long total = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
		{
			total += Math.max(0, gc.getCollectionTime());
		}
		return total;
	}

	/** One logged geometry drawn under its ground truth, over a fixed noisy frame. */
	private static final class Scene
	{
		private final String name;
		private final LoggedAmbiguousReplayTest.Replay replay;
		private final SubmenuTree tree;
		private final int[] pixels;
		private final List<Rectangle> drawn = new ArrayList<>();

		Scene(String[] record)
		{
			replay = new LoggedAmbiguousReplayTest.Replay(record);
			int truth = -1;
			for (int hypothesis : replay.hypotheses())
			{
				SubmenuPrototypeProbe.Result result = replay.probe(hypothesis);
				if (Arrays.equals(replay.changed, result.changed) && Arrays.equals(replay.sharedChanged, result.sharedChanged))
				{
					truth = hypothesis;
				}
			}
			name = record[0] + ":" + record[1] + ":h" + truth;
			tree = SubmenuTree.capture(replay.root);
			pixels = ProbeSemanticsGoldenTest.noise(new Random(record[1].hashCode()), replay.width * replay.height);
			drawn.add(ExactCoverageResolverTest.bounds(replay.root));
			if (truth >= 0)
			{
				drawn.add(ExactCoverageResolverTest.bounds(replay.candidates.get(truth)));
			}
		}

		long candidatePixels()
		{
			long total = 0;
			Rectangle canvas = new Rectangle(0, 0, replay.width, replay.height);
			for (Rectangle bounds : tree.candidateBounds)
			{
				Rectangle visible = bounds.intersection(canvas);
				total += visible.isEmpty() ? 0 : (long) visible.width * visible.height;
			}
			return total;
		}

		SubmenuPrototypeProbe.Result run(SubmenuPrototypeProbe probe)
		{
			return probe.samplePixels(pixels, replay.width, replay.height, tree,
				() -> ProbeSemanticsGoldenTest.paint(pixels, replay.width, replay.height, drawn));
		}
	}
}
