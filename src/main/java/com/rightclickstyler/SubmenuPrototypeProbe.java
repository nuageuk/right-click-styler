package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.Rasterizer;

/** Temporary native draw probe. Every framebuffer write is undone in finally. */
final class SubmenuPrototypeProbe
{
	private static final int SENTINEL = 0x13579BDF;
	private static final int MARGIN = 2;
	private static final int EDGE_TOLERANCE = 3;
	private static final long MAX_FOOTPRINT_SCAN_PIXELS = 1_048_576;
	// At most 8 MiB of exclusive witness positions, plus bounded shared storage.
	private static final long MAX_CANDIDATE_AREA_PIXELS = 2_097_152;
	private int[] snapshot = new int[0];
	private short[] overlapCoverage = new short[0];
	private int[][] witnessBuffers = new int[0][];
	private int[][] sharedBuffers = new int[0][];
	private byte[] footprintMask = new byte[0];
	private int[] footprintPositions = new int[0];
	private int[] changedFootprint = new int[0];
	private final long[] phaseTimes = new long[5];

	Result probe(Client client, SubmenuTree tree)
	{
		Rasterizer raster = client.getRasterizer();
		BufferProvider provider = client.getBufferProvider();
		if (raster == null || provider == null)
		{
			return Result.failure(State.UNSUPPORTED, "raster/provider mismatch");
		}
		int[] pixels = raster.getPixels();
		int width = raster.getWidth();
		int height = raster.getHeight();
		if (pixels == null || pixels != provider.getPixels()
			|| width != provider.getWidth() || height != provider.getHeight()
			|| width <= 0 || height <= 0 || (long) width * height > pixels.length)
		{
			return Result.failure(State.UNSUPPORTED, "raster/provider mismatch");
		}
		Result result = samplePixels(pixels, width, height, tree,
			() -> client.drawOriginalMenu(255));
		Rasterizer afterRaster = client.getRasterizer();
		BufferProvider afterProvider = client.getBufferProvider();
		if (afterRaster == null || afterProvider == null || afterRaster.getPixels() != pixels
			|| afterProvider.getPixels() != pixels || afterRaster.getWidth() != width
			|| afterRaster.getHeight() != height || afterProvider.getWidth() != width
			|| afterProvider.getHeight() != height)
		{
			return Result.failure(State.FAILURE, "buffer identity or dimensions changed during draw");
		}
		return result;
	}

	/** Also used by isolated synthetic tests. */
	static Result probePixels(int[] pixels, int width, int height, SubmenuTree tree, Runnable nativeDraw)
	{
		return new SubmenuPrototypeProbe().samplePixels(pixels, width, height, tree, nativeDraw);
	}

	Result samplePixels(int[] pixels, int width, int height, SubmenuTree tree, Runnable nativeDraw)
	{
		return samplePixelsPass(pixels, width, height, tree, nativeDraw).withPhases(phaseTimes);
	}

	private Result samplePixelsPass(int[] pixels, int width, int height,
		SubmenuTree tree, Runnable nativeDraw)
	{
		long start = System.nanoTime();
		Arrays.fill(phaseTimes, 0);
		if (pixels == null || width <= 0 || height <= 0 || (long) width * height > pixels.length)
		{
			return Result.failure(State.UNSUPPORTED, "invalid framebuffer");
		}
		List<Rectangle> candidates = tree.candidateBounds;
		Rectangle canvas = new Rectangle(0, 0, width, height);
		Rectangle root = tree.nodes.get(0).bounds.intersection(canvas);
		int[] visibleCandidates = new int[candidates.size()];
		int visibleCount = 0;
		long totalVisibleArea = 0;
		for (int i = 0; i < candidates.size(); i++)
		{
			Rectangle child = candidates.get(i).intersection(canvas);
			if (child.width > 0 && child.height > 0)
			{
				totalVisibleArea += (long) child.width * child.height;
				if (totalVisibleArea > MAX_CANDIDATE_AREA_PIXELS)
				{
					return Result.failure(State.UNSUPPORTED, "candidate pixel area limit exceeded area="
						+ totalVisibleArea + " limit=" + MAX_CANDIDATE_AREA_PIXELS);
				}
				visibleCandidates[visibleCount++] = i;
			}
		}
		long coverageAllocation = 0;
		if (overlapCoverage.length != pixels.length)
		{
			overlapCoverage = new short[pixels.length];
			coverageAllocation = 2L * pixels.length;
		}
		// A root-only frame reads coverage only inside the root. Avoid clearing
		// the entire framebuffer when every child has dormant or offscreen bounds.
		if (visibleCount > 0)
		{
			Arrays.fill(overlapCoverage, (short) 0);
		}
		else
		{
			clearCoverage(overlapCoverage, width, root.x, root.y,
				root.x + root.width, root.y + root.height);
		}
		for (int j = 0; j < visibleCount; j++)
		{
			Rectangle r = candidates.get(visibleCandidates[j]);
			int left = Math.max(0, r.x - MARGIN);
			int top = Math.max(0, r.y - MARGIN);
			int right = Math.min(width, r.x + r.width + MARGIN);
			int bottom = Math.min(height, r.y + r.height + MARGIN);
			for (int y = top; y < bottom; y++)
			{
				int position = y * width + left;
				for (int x = left; x < right; x++)
				{
					overlapCoverage[position++]++;
				}
			}
		}
		if (witnessBuffers.length < candidates.size())
		{
			witnessBuffers = new int[candidates.size()][];
			sharedBuffers = new int[candidates.size()][];
		}
		int[] counts = new int[candidates.size()];
		int[] changes = new int[candidates.size()];
		int[] sharedCounts = new int[candidates.size()];
		int[] sharedChanges = new int[candidates.size()];
		boolean[] dormant = new boolean[candidates.size()];
		boolean complex = false;
		long candidateArea = 0;
		long allocatedBytesMin = 20L * candidates.size() + coverageAllocation;
		for (int i = 0; i < candidates.size(); i++)
		{
			Rectangle nativeBounds = candidates.get(i);
			Rectangle child = nativeBounds.intersection(canvas);
			if (nativeBounds.width == 0 || nativeBounds.height == 0
				|| child.width <= 0 || child.height <= 0)
			{
				dormant[i] = true;
				continue;
			}
			int[] positions = witnessBuffers[i];
			if (positions == null || positions.length < child.width * child.height)
			{
				positions = new int[child.width * child.height];
				witnessBuffers[i] = positions;
				allocatedBytesMin += 4L * positions.length;
			}
			for (int y = child.y + MARGIN; y < child.y + child.height - MARGIN; y++)
			{
				for (int x = child.x + MARGIN; x < child.x + child.width - MARGIN; x++)
				{
					if (!contains(root, x, y) && overlapCoverage[y * width + x] == 1)
					{
						positions[counts[i]++] = y * width + x;
					}
				}
			}
			if (counts[i] == 0)
			{
				complex = true;
				// Overlapping siblings can have no exclusive pixels. Sample their visible
				// area outside the root to prove a dormant child was not drawn.
				int[] shared = sharedBuffers[i];
				if (shared == null || shared.length < child.width * child.height)
				{
					shared = new int[child.width * child.height];
					sharedBuffers[i] = shared;
					allocatedBytesMin += 4L * shared.length;
				}
				int insetX = Math.min(MARGIN, (child.width - 1) / 2);
				int insetY = Math.min(MARGIN, (child.height - 1) / 2);
				for (int y = child.y + insetY; y < child.y + child.height - insetY; y++)
				{
					for (int x = child.x + insetX; x < child.x + child.width - insetX; x++)
					{
						if (!contains(root, x, y))
						{
							shared[sharedCounts[i]++] = y * width + x;
						}
					}
				}
			}
			candidateArea += (long) child.width * child.height;
		}
		boolean footprintEnabled = complex && candidateArea <= MAX_FOOTPRINT_SCAN_PIXELS;
		int[] visibleArea = null;
		int[] visibleMinX = null;
		int[] visibleMinY = null;
		int[] visibleMaxX = null;
		int[] visibleMaxY = null;
		if (footprintEnabled)
		{
			visibleArea = new int[candidates.size()];
			visibleMinX = new int[candidates.size()];
			visibleMinY = new int[candidates.size()];
			visibleMaxX = new int[candidates.size()];
			visibleMaxY = new int[candidates.size()];
			Arrays.fill(visibleMinX, width);
			Arrays.fill(visibleMinY, height);
			Arrays.fill(visibleMaxX, -1);
			Arrays.fill(visibleMaxY, -1);
			allocatedBytesMin += 20L * candidates.size();
			int capacity = (int) Math.min(pixels.length, candidateArea);
			if (footprintMask.length != pixels.length)
			{
				footprintMask = new byte[pixels.length];
				allocatedBytesMin += footprintMask.length;
			}
			if (footprintPositions.length < capacity)
			{
				footprintPositions = new int[capacity];
				changedFootprint = new int[capacity];
				allocatedBytesMin += 8L * capacity;
			}
		}

		if (snapshot.length != pixels.length)
		{
			snapshot = new int[pixels.length];
			allocatedBytesMin += 4L * snapshot.length;
		}
		phaseTimes[0] = System.nanoTime() - start;
		int[] before = snapshot;
		long copyStart = System.nanoTime();
		System.arraycopy(pixels, 0, before, 0, pixels.length);
		phaseTimes[1] = System.nanoTime() - copyStart;
		int rootWitness = 0;
		int rootChanged = 0;
		int footprintCount = 0;
		int footprintChanged = 0;
		long drawNanos = 0;
		boolean restored;
		long markerStart = System.nanoTime();
		try
		{
			for (int y = root.y; y < root.y + root.height; y++)
			{
				for (int x = root.x; x < root.x + root.width; x++)
				{
					if (overlapCoverage[y * width + x] == 0)
					{
						pixels[y * width + x] = SENTINEL;
						rootWitness++;
					}
				}
			}
			for (int i = 0; i < candidates.size(); i++)
			{
				for (int j = 0; j < counts[i]; j++)
				{
					pixels[witnessBuffers[i][j]] = SENTINEL;
				}
				for (int j = 0; j < sharedCounts[i]; j++)
				{
					pixels[sharedBuffers[i][j]] = SENTINEL;
				}
			}
			if (footprintEnabled)
			{
				// One marker over the union, so overlap never counts as evidence
				// for a particular child. This uses the existing native draw.
				for (int i = 0; i < candidates.size(); i++)
				{
					if (dormant[i])
					{
						continue;
					}
					Rectangle child = candidates.get(i).intersection(canvas);
					int insetX = Math.min(MARGIN, (child.width - 1) / 2);
					int insetY = Math.min(MARGIN, (child.height - 1) / 2);
					for (int y = child.y + insetY; y < child.y + child.height - insetY; y++)
					{
						for (int x = child.x + insetX; x < child.x + child.width - insetX; x++)
						{
							if (contains(root, x, y))
							{
								continue;
							}
							visibleArea[i]++;
							visibleMinX[i] = Math.min(visibleMinX[i], x);
							visibleMinY[i] = Math.min(visibleMinY[i], y);
							visibleMaxX[i] = Math.max(visibleMaxX[i], x);
							visibleMaxY[i] = Math.max(visibleMaxY[i], y);
							int position = y * width + x;
							if (footprintMask[position] == 0)
							{
								footprintMask[position] = 1;
								footprintPositions[footprintCount++] = position;
								pixels[position] = SENTINEL;
							}
						}
					}
				}
			}
			phaseTimes[2] = System.nanoTime() - markerStart;
			long drawStart = System.nanoTime();
			nativeDraw.run();
			drawNanos = System.nanoTime() - drawStart;
			long compareStart = System.nanoTime();
			for (int y = root.y; y < root.y + root.height; y++)
			{
				for (int x = root.x; x < root.x + root.width; x++)
				{
					if (overlapCoverage[y * width + x] == 0
						&& pixels[y * width + x] != SENTINEL)
					{
						rootChanged++;
					}
				}
			}
			for (int i = 0; i < candidates.size(); i++)
			{
				for (int j = 0; j < counts[i]; j++)
				{
					if (pixels[witnessBuffers[i][j]] != SENTINEL)
					{
						changes[i]++;
					}
				}
				for (int j = 0; j < sharedCounts[i]; j++)
				{
					if (pixels[sharedBuffers[i][j]] != SENTINEL)
					{
						sharedChanges[i]++;
					}
				}
			}
			if (footprintEnabled)
			{
				for (int i = 0; i < footprintCount; i++)
				{
					int position = footprintPositions[i];
					if (pixels[position] != SENTINEL)
					{
						changedFootprint[footprintChanged++] = position;
					}
				}
			}
			phaseTimes[3] = System.nanoTime() - compareStart;
		}
		finally
		{
			long restoreStart = System.nanoTime();
			System.arraycopy(before, 0, pixels, 0, before.length);
			for (int i = 0; i < footprintCount; i++)
			{
				footprintMask[footprintPositions[i]] = 0;
			}
			restored = Arrays.equals(before, pixels);
			phaseTimes[4] = System.nanoTime() - restoreStart;
		}
		if (restored)
		{
			if (geometryChanged(tree.nodes.get(0).menu, tree.nodes.get(0).bounds))
			{
				return Result.afterDraw(State.GEOMETRY_CHANGED, "root geometry changed during probe",
					start, counts, changes, sharedCounts, sharedChanges,
					drawNanos, before.length, allocatedBytesMin, true);
			}
			for (int i = 0; i < candidates.size(); i++)
			{
				if (geometryChanged(tree.nodes.get(i + 1).menu, candidates.get(i)))
				{
					return Result.afterDraw(State.GEOMETRY_CHANGED, "child geometry changed during probe",
						start, counts, changes, sharedCounts, sharedChanges,
						drawNanos, before.length, allocatedBytesMin, true);
				}
			}
		}
		if (!restored || rootWitness == 0 || rootChanged == 0)
		{
			return Result.afterDraw(!restored ? State.FAILURE
				: rootWitness == 0 ? State.AMBIGUOUS : State.FAILURE,
				!restored ? "framebuffer restoration failed"
					: rootWitness == 0 ? "ambiguous root geometry" : "native draw missed buffer",
				start, counts, changes, sharedCounts, sharedChanges,
				drawNanos, before.length, allocatedBytesMin, restored);
		}
		int[] chain = activeChain(tree.candidateParents, changes);
		if (chain == null)
		{
			if (footprintEnabled)
			{
				FootprintResolution footprint = resolveFootprint(width, tree, dormant,
					visibleArea, visibleMinX, visibleMinY, visibleMaxX, visibleMaxY,
					footprintChanged);
				if (footprint.matches == 1 && matchesExclusiveEvidence(footprint.chain, changes))
				{
					return new Result(footprint.chain.length == 0 ? State.NO_CHILD : State.ACTIVE,
						"footprint matched", footprint.chain, counts, changes, sharedCounts, sharedChanges,
						System.nanoTime() - start, drawNanos, before.length, allocatedBytesMin, true)
						.withFootprint(footprint);
				}
				return Result.afterDraw(State.AMBIGUOUS, "ambiguous detection footprintMatches="
					+ footprint.matches, start, counts, changes, sharedCounts, sharedChanges,
					drawNanos, before.length, allocatedBytesMin, true).withFootprint(footprint);
			}
			return Result.afterDraw(State.AMBIGUOUS, "ambiguous detection",
				start, counts, changes, sharedCounts, sharedChanges,
				drawNanos, before.length, allocatedBytesMin, true);
		}
		int unresolved = -1;
		for (int i = 0; i < candidates.size(); i++)
		{
			if (!dormant[i] && counts[i] == 0 && couldBeOnChain(i, tree.candidateParents, chain)
				&& (sharedCounts[i] == 0 || sharedChanges[i] > 0))
			{
				unresolved = i;
				break;
			}
		}
		if (unresolved >= 0)
		{
			if (!footprintEnabled)
			{
				return Result.afterDraw(State.AMBIGUOUS, "footprint scan limit exceeded",
					start, counts, changes, sharedCounts, sharedChanges,
					drawNanos, before.length, allocatedBytesMin, true);
			}
			FootprintResolution footprint = resolveFootprint(width, tree, dormant,
				visibleArea, visibleMinX, visibleMinY, visibleMaxX, visibleMaxY,
				footprintChanged);
			if (footprint.matches == 1 && matchesExclusiveEvidence(footprint.chain, changes))
			{
				return new Result(footprint.chain.length == 0 ? State.NO_CHILD : State.ACTIVE,
					"footprint matched", footprint.chain, counts, changes, sharedCounts, sharedChanges,
					System.nanoTime() - start, drawNanos, before.length, allocatedBytesMin, true)
					.withFootprint(footprint);
			}
			return Result.afterDraw(State.AMBIGUOUS, "ambiguous geometry index=" + unresolved
				+ " bounds=" + candidates.get(unresolved) + " shared=" + sharedCounts[unresolved]
				+ " changed=" + sharedChanges[unresolved] + " footprintMatches=" + footprint.matches,
				start, counts, changes, sharedCounts, sharedChanges,
				drawNanos, before.length, allocatedBytesMin, true).withFootprint(footprint);
		}
		return new Result(chain.length == 0 ? State.NO_CHILD : State.ACTIVE,
			"sampled", chain, counts, changes, sharedCounts, sharedChanges,
			System.nanoTime() - start, drawNanos,
			before.length, allocatedBytesMin, true);
	}

	private static void clearCoverage(short[] coverage, int width,
		int left, int top, int right, int bottom)
	{
		if (right <= left || bottom <= top)
		{
			return;
		}
		for (int y = top; y < bottom; y++)
		{
			Arrays.fill(coverage, y * width + left, y * width + right, (short) 0);
		}
	}

	private static boolean geometryChanged(Menu menu, Rectangle bounds)
	{
		return menu.getMenuX() != bounds.x || menu.getMenuY() != bounds.y
			|| menu.getMenuWidth() != bounds.width || menu.getMenuHeight() != bounds.height;
	}

	private static boolean couldBeOnChain(int candidate, List<Integer> parents, int[] chain)
	{
		int parent = parents.get(candidate);
		if (parent < 0)
		{
			return chain.length == 0 || chain[0] == candidate;
		}
		for (int depth = 0; depth < chain.length; depth++)
		{
			if (chain[depth] == parent)
			{
				return depth + 1 == chain.length || chain[depth + 1] == candidate;
			}
		}
		return false;
	}

	private static boolean matchesExclusiveEvidence(int[] chain, int[] changes)
	{
		for (int i = 0; i < changes.length; i++)
		{
			if (changes[i] > 0)
			{
				boolean onChain = false;
				for (int member : chain)
				{
					onChain |= member == i;
				}
				if (!onChain)
				{
					return false;
				}
			}
		}
		return true;
	}

	/** Resolve only the cases that exclusive witnesses cannot identify. */
	private FootprintResolution resolveFootprint(int width, SubmenuTree tree, boolean[] dormant,
		int[] visibleArea, int[] minX, int[] minY, int[] maxX, int[] maxY, int changedCount)
	{
		int size = tree.candidateBounds.size();
		if (changedCount == 0)
		{
			for (int i = 0; i < size; i++)
			{
				if (!dormant[i] && visibleArea[i] == 0)
				{
					return new FootprintResolution(new int[0], 0, null, 0);
				}
			}
			return new FootprintResolution(new int[0], 1, null, 0);
		}
		int actualMinX = width;
		int actualMinY = Integer.MAX_VALUE;
		int actualMaxX = -1;
		int actualMaxY = -1;
		int[] hits = new int[size];
		for (int j = 0; j < changedCount; j++)
		{
			int x = changedFootprint[j] % width;
			int y = changedFootprint[j] / width;
			actualMinX = Math.min(actualMinX, x);
			actualMinY = Math.min(actualMinY, y);
			actualMaxX = Math.max(actualMaxX, x);
			actualMaxY = Math.max(actualMaxY, y);
			for (int i = 0; i < size; i++)
			{
				Rectangle candidate = tree.candidateBounds.get(i);
				if (x >= candidate.x && x < candidate.x + candidate.width
					&& y >= candidate.y && y < candidate.y + candidate.height)
				{
					hits[i]++;
				}
			}
		}
		Rectangle actual = new Rectangle(actualMinX, actualMinY,
			actualMaxX - actualMinX + 1, actualMaxY - actualMinY + 1);
		int[] reverse = new int[size];
		int[] selected = new int[0];
		int matches = 0;
		for (int leaf = 0; leaf < size; leaf++)
		{
			int depth = 0;
			int current = leaf;
			int expectedMinX = width;
			int expectedMinY = Integer.MAX_VALUE;
			int expectedMaxX = -1;
			int expectedMaxY = -1;
			boolean valid = true;
			while (current >= 0)
			{
				if (dormant[current])
				{
					valid = false;
					break;
				}
				reverse[depth++] = current;
				if (visibleArea[current] > 0)
				{
					expectedMinX = Math.min(expectedMinX, minX[current]);
					expectedMinY = Math.min(expectedMinY, minY[current]);
					expectedMaxX = Math.max(expectedMaxX, maxX[current]);
					expectedMaxY = Math.max(expectedMaxY, maxY[current]);
					if ((long) hits[current] * 100 < (long) visibleArea[current] * 90)
					{
						valid = false;
					}
				}
				current = tree.candidateParents.get(current);
			}
			if (!valid || expectedMaxX < 0
				|| Math.abs(actualMinX - expectedMinX) > EDGE_TOLERANCE
				|| Math.abs(actualMinY - expectedMinY) > EDGE_TOLERANCE
				|| Math.abs(actualMaxX - expectedMaxX) > EDGE_TOLERANCE
				|| Math.abs(actualMaxY - expectedMaxY) > EDGE_TOLERANCE)
			{
				continue;
			}
			for (int j = 0; j < changedCount; j++)
			{
				int x = changedFootprint[j] % width;
				int y = changedFootprint[j] / width;
				boolean covered = false;
				for (int k = 0; k < depth; k++)
				{
					covered |= containsWithMargin(tree.candidateBounds.get(reverse[k]), x, y);
				}
				if (!covered)
				{
					valid = false;
					break;
				}
			}
			if (valid)
			{
				matches++;
				if (matches == 1)
				{
					selected = new int[depth];
					for (int j = 0; j < depth; j++)
					{
						selected[j] = reverse[depth - 1 - j];
					}
				}
			}
		}
		return new FootprintResolution(selected, matches, actual, changedCount);
	}

	private static final class FootprintResolution
	{
		private final int[] chain;
		private final int matches;
		private final Rectangle bounds;
		private final int changedPixels;

		private FootprintResolution(int[] chain, int matches, Rectangle bounds, int changedPixels)
		{
			this.chain = chain;
			this.matches = matches;
			this.bounds = bounds;
			this.changedPixels = changedPixels;
		}
	}

	private static int[] activeChain(List<Integer> parents, int[] changed)
	{
		int[] chain = new int[changed.length];
		int size = 0;
		int parent = -1;
		while (true)
		{
			int next = -1;
			for (int i = 0; i < changed.length; i++)
			{
				if (changed[i] > 0 && parents.get(i) == parent)
				{
					if (next >= 0)
					{
						return null;
					}
					next = i;
				}
			}
			if (next < 0)
			{
				int hits = 0;
				for (int changedCount : changed)
				{
					if (changedCount > 0)
					{
						hits++;
					}
				}
				return hits == size ? Arrays.copyOf(chain, size) : null;
			}
			chain[size++] = next;
			parent = next;
		}
	}

	private static boolean contains(Rectangle r, int x, int y)
	{
		return containsWithMargin(r, x, y);
	}

	private static boolean containsWithMargin(Rectangle r, int x, int y)
	{
		return x >= r.x - MARGIN && x < r.x + r.width + MARGIN
			&& y >= r.y - MARGIN && y < r.y + r.height + MARGIN;
	}

	enum State
	{
		NO_CHILD, ACTIVE, AMBIGUOUS, UNSUPPORTED, GEOMETRY_CHANGED, FAILURE
	}

	static final class Result
	{
		final State state;
		final String reason;
		final int[] activeChain;
		final int[] witnesses;
		final int[] changed;
		final int[] sharedWitnesses;
		final int[] sharedChanged;
		final long nanoseconds;
		final long drawNanos;
		final int copiedPixels;
		final long allocatedBytesMin;
		final boolean restored;
		int drawPasses;
		long preparationNanos;
		long copyNanos;
		long markerNanos;
		long comparisonNanos;
		long restoreNanos;
		long decisionNanos;
		int footprintMatches;
		int footprintChangedPixels;
		Rectangle footprintBounds;

		private Result(State state, String reason, int[] activeChain, int[] witnesses,
			int[] changed, int[] sharedWitnesses, int[] sharedChanged,
			long nanoseconds, long drawNanos, int copiedPixels,
			long allocatedBytesMin, boolean restored)
		{
			this.state = state;
			this.reason = reason;
			this.activeChain = activeChain;
			this.witnesses = witnesses;
			this.changed = changed;
			this.sharedWitnesses = sharedWitnesses;
			this.sharedChanged = sharedChanged;
			this.nanoseconds = nanoseconds;
			this.drawNanos = drawNanos;
			this.copiedPixels = copiedPixels;
			this.allocatedBytesMin = allocatedBytesMin;
			this.restored = restored;
			this.drawPasses = copiedPixels > 0 ? 1 : 0;
		}

		private Result withPhases(long[] phases)
		{
			preparationNanos = phases[0];
			copyNanos = phases[1];
			markerNanos = phases[2];
			comparisonNanos = phases[3];
			restoreNanos = phases[4];
			decisionNanos = Math.max(0, nanoseconds - drawNanos
				- preparationNanos - copyNanos - markerNanos
				- comparisonNanos - restoreNanos);
			return this;
		}

		private Result withFootprint(FootprintResolution footprint)
		{
			footprintMatches = footprint.matches;
			footprintChangedPixels = footprint.changedPixels;
			footprintBounds = footprint.bounds;
			return this;
		}

		private static Result failure(State state, String reason)
		{
			return failure(state, reason, false);
		}

		private static Result failure(State state, String reason, boolean restored)
		{
			return new Result(state, reason, new int[0], new int[0],
				new int[0], new int[0], new int[0], 0, 0, 0, 0, restored);
		}

		private static Result afterDraw(State state, String reason, long start,
			int[] witnesses, int[] changed, int[] sharedWitnesses, int[] sharedChanged,
			long drawNanos, int copiedPixels,
			long allocatedBytesMin, boolean restored)
		{
			return new Result(state, reason, new int[0], witnesses, changed,
				sharedWitnesses, sharedChanged,
				System.nanoTime() - start, drawNanos, copiedPixels, allocatedBytesMin, restored);
		}
	}
}
