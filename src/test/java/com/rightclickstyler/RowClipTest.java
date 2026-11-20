package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExactCoverageResolver#outsideRoot} must select exactly the x for which the per-pixel
 * {@code insideRoot} test is false, in ascending order, for every root shape the probe can see:
 * ordinary, clipped at each canvas edge, zero and negative size (the intersection of an
 * offscreen root), and margins of 0-3.
 */
public class RowClipTest
{
	@Test
	public void segmentsMatchThePerPixelTest()
	{
		Random random = new Random(42);
		int[] segments = new int[4];
		int checkedRows = 0;
		for (int trial = 0; trial < 20_000; trial++)
		{
			Rectangle root = new Rectangle(random.nextInt(80) - 20, random.nextInt(80) - 20,
				random.nextInt(60) - 10, random.nextInt(60) - 10);
			if (trial % 7 == 0)
			{
				// Offscreen roots: Rectangle.intersection with the canvas gives these sizes.
				root = new Rectangle(0, random.nextInt(40), -random.nextInt(6), random.nextInt(30));
			}
			int margin = random.nextInt(4);
			int from = random.nextInt(100) - 30;
			int to = from + random.nextInt(90) - 5;
			for (int y = -30; y < 90; y++)
			{
				int parts = ExactCoverageResolver.outsideRoot(root, margin, y, from, to, segments);
				assertTrue(parts >= 0 && parts <= 2);
				boolean[] selected = new boolean[Math.max(0, to - from)];
				int previousEnd = Integer.MIN_VALUE;
				for (int s = 0; s < parts; s++)
				{
					int a = segments[2 * s];
					int b = segments[2 * s + 1];
					assertTrue("segments are non-empty, ascending and disjoint", a < b && a >= previousEnd && a >= from && b <= to);
					previousEnd = b;
					for (int x = a; x < b; x++)
					{
						selected[x - from] = true;
					}
				}
				for (int x = from; x < to; x++)
				{
					assertEquals("root=" + root + " margin=" + margin + " y=" + y + " x=" + x,
						!ExactCoverageResolver.insideRoot(root, margin, x, y), selected[x - from]);
				}
				checkedRows++;
			}
		}
		assertTrue(checkedRows > 2_000_000);
	}
}
