package com.rightclickstyler;

import java.lang.reflect.Field;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A repeated probe of an unchanged menu reuses every scratch buffer instead of reallocating. */
public class ProbeBufferReuseTest
{
	private static final String[] BUFFERS = {
		"snapshot", "overlapCoverage", "footprintMask", "footprintPositions", "changedFootprint",
		"exclusivePool", "sharedPool"};

	@Test
	public void steadyStatePassesAllocateNoScratchBuffers() throws Exception
	{
		for (String[] record : LoggedAmbiguousReplayTest.records())
		{
			LoggedAmbiguousReplayTest.Replay replay = new LoggedAmbiguousReplayTest.Replay(record);
			int[] pixels = new int[replay.width * replay.height];
			SubmenuTree tree = SubmenuTree.capture(replay.root);
			NativeSubmenuProbe probe = new NativeSubmenuProbe();
			Runnable draw = () -> ProbeSemanticsGoldenTest.paint(pixels, replay.width, replay.height,
				Collections.singletonList(ExactCoverageResolverTest.bounds(replay.root)));
			probe.samplePixels(pixels, replay.width, replay.height, tree, draw);
			Object[] first = buffers(probe);
			assertEquals(record[1] + ": snapshot sized by the first pass", pixels.length, ((int[]) first[0]).length);
			assertTrue(record[1] + ": witness pool grown by the first pass", ((int[]) first[5]).length > 0);
			probe.samplePixels(pixels, replay.width, replay.height, tree, draw);
			Object[] second = buffers(probe);
			for (int i = 0; i < BUFFERS.length; i++)
			{
				assertSame(record[1] + ": " + BUFFERS[i] + " reallocated", first[i], second[i]);
			}
			assertTrue(probe.scratchClean());
		}
	}

	private static Object[] buffers(NativeSubmenuProbe probe) throws Exception
	{
		Object[] values = new Object[BUFFERS.length];
		for (int i = 0; i < BUFFERS.length; i++)
		{
			Field field = NativeSubmenuProbe.class.getDeclaredField(BUFFERS[i]);
			field.setAccessible(true);
			values[i] = field.get(probe);
		}
		return values;
	}
}
