package com.rightclickstyler;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The probe's phase timings and work counters describe the pass they came from. */
public class ProbeInstrumentationTest
{
	@Test
	public void phasesAreExclusiveAndSumToThePass() throws Exception
	{
		SubmenuPrototypeProbe probe = new SubmenuPrototypeProbe();
		probe.requireExact(true);
		for (String[] record : LoggedAmbiguousReplayTest.records())
		{
			LoggedAmbiguousReplayTest.Replay replay = new LoggedAmbiguousReplayTest.Replay(record);
			int[] pixels = new int[replay.width * replay.height];
			SubmenuTree tree = SubmenuTree.capture(replay.root);
			SubmenuPrototypeProbe.Result result = probe.samplePixels(pixels, replay.width, replay.height, tree,
				() -> ProbeSemanticsGoldenTest.paint(pixels, replay.width, replay.height,
					java.util.Collections.singletonList(ExactCoverageResolverTest.bounds(replay.root))));
			long sum = 0;
			for (long nanos : result.phaseNanos)
			{
				assertTrue(nanos >= 0);
				sum += nanos;
			}
			assertEquals("phases must partition the pass", result.passNanos, sum);
			assertTrue(result.passNanos >= result.nanoseconds);
			assertEquals(result.drawNanos, result.phase(SubmenuPrototypeProbe.Phase.NATIVE_DRAW), result.drawNanos / 10 + 50_000);

			assertEquals(pixels.length, result.count(SubmenuPrototypeProbe.Counter.FRAME_PIXELS));
			assertEquals(tree.candidateBounds.size(), result.count(SubmenuPrototypeProbe.Counter.CANDIDATES));
			assertEquals(result.visibleCandidates, result.count(SubmenuPrototypeProbe.Counter.VISIBLE_CANDIDATES));
			assertEquals(Arrays.stream(result.witnesses).asLongStream().sum(), result.count(SubmenuPrototypeProbe.Counter.EXCLUSIVE_MARKS));
			assertEquals(Arrays.stream(result.sharedWitnesses).asLongStream().sum(), result.count(SubmenuPrototypeProbe.Counter.SHARED_MARKS));
			assertEquals(result.exact.changedPixels, result.count(SubmenuPrototypeProbe.Counter.EXACT_CHANGED));
		}
	}

	@Test
	public void allocationIsCountedOnlyWhenBuffersGrow() throws Exception
	{
		String[] record = LoggedAmbiguousReplayTest.records().get(0);
		LoggedAmbiguousReplayTest.Replay replay = new LoggedAmbiguousReplayTest.Replay(record);
		int[] pixels = new int[replay.width * replay.height];
		SubmenuTree tree = SubmenuTree.capture(replay.root);
		SubmenuPrototypeProbe probe = new SubmenuPrototypeProbe();
		probe.requireExact(true);
		Runnable draw = () -> ProbeSemanticsGoldenTest.paint(pixels, replay.width, replay.height,
			java.util.Collections.singletonList(ExactCoverageResolverTest.bounds(replay.root)));
		SubmenuPrototypeProbe.Result first = probe.samplePixels(pixels, replay.width, replay.height, tree, draw);
		SubmenuPrototypeProbe.Result second = probe.samplePixels(pixels, replay.width, replay.height, tree, draw);
		assertTrue(first.count(SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES) >= 6L * pixels.length);
		assertEquals(0, second.count(SubmenuPrototypeProbe.Counter.ALLOCATED_BYTES));
		assertEquals(0, second.phase(SubmenuPrototypeProbe.Phase.ALLOCATE));
	}

	@Test
	public void perfTraceBucketsProbesByVisibleCandidates() throws Exception
	{
		assertEquals(PerfTrace.ProbeClass.ROOT_ONLY, PerfTrace.ProbeClass.of(0));
		assertEquals(PerfTrace.ProbeClass.LIGHT_1_4, PerfTrace.ProbeClass.of(4));
		assertEquals(PerfTrace.ProbeClass.CHILD_5_19, PerfTrace.ProbeClass.of(5));
		assertEquals(PerfTrace.ProbeClass.CHILD_5_19, PerfTrace.ProbeClass.of(19));
		assertEquals(PerfTrace.ProbeClass.HEAVY_20_PLUS, PerfTrace.ProbeClass.of(20));

		PerfTrace perf = new PerfTrace();
		SubmenuPrototypeProbe probe = new SubmenuPrototypeProbe();
		for (String[] record : LoggedAmbiguousReplayTest.records())
		{
			LoggedAmbiguousReplayTest.Replay replay = new LoggedAmbiguousReplayTest.Replay(record);
			int[] pixels = new int[replay.width * replay.height];
			perf.probe(probe.samplePixels(pixels, replay.width, replay.height, SubmenuTree.capture(replay.root), () -> { }));
		}
		int total = 0;
		for (PerfTrace.ProbeClass probeClass : PerfTrace.ProbeClass.values())
		{
			total += perf.probeTotals(probeClass).count();
			assertEquals(perf.probeTotals(probeClass).count(),
				perf.probePhase(probeClass, SubmenuPrototypeProbe.Phase.SNAPSHOT).count());
		}
		assertEquals(LoggedAmbiguousReplayTest.records().size(), total);
		// Two windows: the first report only opens the window, the second logs and clears it.
		org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProbeInstrumentationTest.class);
		perf.report(log, null, 1);
		perf.report(log, null, 10_000_000_000L);
		assertEquals(0, perf.probeTotals(PerfTrace.ProbeClass.HEAVY_20_PLUS).count());
	}
}
