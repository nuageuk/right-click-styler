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
	// The exact-coverage reading never changes this probe's own decision. The overlay may
	// require it (authoritative mode) even when the shadow property disables it.
	private static final boolean EXACT_COVERAGE_SHADOW =
		!"false".equals(System.getProperty("rightclickstyler.exactCoverageShadow"));
	private static final int MARGIN = 2;
	private static final int EDGE_TOLERANCE = 3;
	private static final long MAX_FOOTPRINT_SCAN_PIXELS = 1_048_576;
	// At most 8 MiB of exclusive witness positions, plus bounded shared storage.
	private static final long MAX_CANDIDATE_AREA_PIXELS = 2_097_152;
	private int[] snapshot = new int[0];

	/**
	 * Cumulative probe optimisation level, for A/B comparison ({@code -Drightclickstyler.probeOpt}).
	 * 0 is the original implementation. Every level reads the same evidence and decides the same:
	 * 1 skips shared sentinel writes the footprint marking repeats, 2 gives the exact resolver
	 * each marked position once, 3 clips rows against the root instead of testing each pixel,
	 * 4 pools witness storage with geometric growth.
	 */
	static final int MAX_OPTIMISATION = 4;
	static final int DEFAULT_OPTIMISATION = parseLevel(System.getProperty("rightclickstyler.probeOpt"));
	private int optimisation = DEFAULT_OPTIMISATION;

	private static int parseLevel(String value)
	{
		try
		{
			return value == null ? MAX_OPTIMISATION : Math.max(0, Math.min(MAX_OPTIMISATION, Integer.parseInt(value.trim())));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	void setOptimisation(int level)
	{
		optimisation = Math.max(0, Math.min(MAX_OPTIMISATION, level));
	}

	int optimisation()
	{
		return optimisation;
	}

	/** Test-only view of a pass's intermediate state; null outside tests. */
	interface Observer
	{
		/** The framebuffer exactly as marked, immediately before the native draw. */
		void beforeNativeDraw(int[] pixels, int width, int height);

		/** The position lists handed to the exact resolver, as {lists[i], sizes[i]}. */
		void resolverInput(int[][] lists, int[] sizes);
	}

	static volatile Observer observer;

	/** Test hook: every scratch mask is back to zero between passes. */
	boolean scratchClean()
	{
		for (byte b : footprintMask)
		{
			if (b != 0)
			{
				return false;
			}
		}
		return exactResolver.maskClean();
	}

	static boolean exactCoverageShadowEnabled()
	{
		return EXACT_COVERAGE_SHADOW;
	}

	private short[] overlapCoverage = new short[0];
	private int[][] witnessBuffers = new int[0][];
	private int[][] sharedBuffers = new int[0][];
	private byte[] footprintMask = new byte[0];
	private int[] footprintPositions = new int[0];
	private int[] changedFootprint = new int[0];
	private final long[] phaseTimes = new long[5];
	// Diagnostic only: exclusive, contiguous laps over the whole probe, and work counters.
	// Reading them never changes what the probe decides.
	private final long[] phaseNanos = new long[Phase.values().length];
	private final long[] counters = new long[Counter.values().length];
	private long lapMark;
	private long allocNanos;
	private long allocAtLap;
	private final int[] segments = new int[4];
	private int[] exclusivePool = new int[0];
	private int[] sharedPool = new int[0];
	private int lastVisibleCandidates;
	private long lastCandidatePixels;
	private final ExactCoverageResolver exactResolver = new ExactCoverageResolver();
	private ExactCoverageResolver.Outcome lastExact = ExactCoverageResolver.Outcome.NOT_EVALUATED;
	private boolean exactRequired;
	private boolean lastFramebufferVerified;

	/** Evaluate the exact-coverage reading on every probe, whatever the shadow property says. */
	void requireExact(boolean required)
	{
		exactRequired = required;
	}

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
			Result failure = Result.failure(State.FAILURE, "buffer identity or dimensions changed during draw");
			failure.drawPasses = result.drawPasses;
			failure.visibleCandidates = result.visibleCandidates;
			failure.candidatePixels = result.candidatePixels;
			return failure;
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
		Arrays.fill(phaseNanos, 0);
		Arrays.fill(counters, 0);
		allocNanos = 0;
		allocAtLap = 0;
		long passStart = System.nanoTime();
		lapMark = passStart;
		Result result = samplePixelsPass(pixels, width, height, tree, nativeDraw).withPhases(phaseTimes);
		long passEnd = System.nanoTime();
		lap(Phase.DECISION, passEnd);
		phaseNanos[Phase.ALLOCATE.ordinal()] = allocNanos;
		long footprintMarks = counters[Counter.FOOTPRINT_MARKS.ordinal()];
		long witnessMarks = counters[Counter.EXCLUSIVE_MARKS.ordinal()] + counters[Counter.SHARED_MARKS.ordinal()];
		long sentinelWrites = counters[Counter.ROOT_WITNESS.ordinal()] + counters[Counter.EXCLUSIVE_MARKS.ordinal()]
			+ counters[Counter.SHARED_WRITES.ordinal()] + footprintMarks;
		counters[Counter.SENTINEL_WRITES.ordinal()] = sentinelWrites;
		// The root is visited twice (mark, compare), each witness position once per write and once
		// per compare, each footprint position after its scan twice more (compare, mask clear) and
		// each resolver input position once (collect) plus each position its clear visits.
		// Early exits leave the later terms zero.
		long rootVisits = counters[Counter.ROOT_WITNESS.ordinal()] > 0 || witnessMarks + footprintMarks > 0
			? 2 * counters[Counter.ROOT_PIXELS.ordinal()] : 0;
		counters[Counter.PIXELS_VISITED.ordinal()] = counters[Counter.COVERAGE_WRITES.ordinal()]
			+ counters[Counter.WITNESS_SCANNED.ordinal()] + rootVisits
			+ counters[Counter.EXCLUSIVE_MARKS.ordinal()] + counters[Counter.SHARED_WRITES.ordinal()] + witnessMarks
			+ counters[Counter.FOOTPRINT_SCANNED.ordinal()] + 2 * footprintMarks
			+ counters[Counter.RESOLVER_POSITIONS.ordinal()] + counters[Counter.RESOLVER_CLEARED.ordinal()]
			+ counters[Counter.RESOLVER_SCANNED.ordinal()];
		result.phaseNanos = phaseNanos.clone();
		result.counters = counters.clone();
		result.passNanos = passEnd - passStart;
		result.visibleCandidates = lastVisibleCandidates;
		result.candidatePixels = lastCandidatePixels;
		result.exact = lastExact;
		result.framebufferVerified = lastFramebufferVerified;
		return result;
	}

	private Result samplePixelsPass(int[] pixels, int width, int height,
		SubmenuTree tree, Runnable nativeDraw)
	{
		long start = System.nanoTime();
		Arrays.fill(phaseTimes, 0);
		lastVisibleCandidates = 0;
		lastCandidatePixels = 0;
		lastExact = ExactCoverageResolver.Outcome.NOT_EVALUATED;
		lastFramebufferVerified = false;
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
		lastVisibleCandidates = visibleCount;
		lastCandidatePixels = totalVisibleArea;
		counters[Counter.FRAME_PIXELS.ordinal()] = pixels.length;
		counters[Counter.CANDIDATES.ordinal()] = candidates.size();
		counters[Counter.VISIBLE_CANDIDATES.ordinal()] = visibleCount;
		counters[Counter.ROOT_PIXELS.ordinal()] = Math.max(0, root.width) * (long) Math.max(0, root.height);
		lap(Phase.VALIDATE);
		long coverageAllocation = 0;
		if (overlapCoverage.length != pixels.length)
		{
			long allocStart = System.nanoTime();
			overlapCoverage = new short[pixels.length];
			allocated(allocStart, 2L * pixels.length);
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
		counters[Counter.COVERAGE_CLEARED.ordinal()] = visibleCount > 0 ? overlapCoverage.length
			: counters[Counter.ROOT_PIXELS.ordinal()];
		lap(Phase.COVERAGE_CLEAR);
		for (int j = 0; j < visibleCount; j++)
		{
			Rectangle r = candidates.get(visibleCandidates[j]);
			int left = Math.max(0, r.x - MARGIN);
			int top = Math.max(0, r.y - MARGIN);
			int right = Math.min(width, r.x + r.width + MARGIN);
			int bottom = Math.min(height, r.y + r.height + MARGIN);
			counters[Counter.COVERAGE_WRITES.ordinal()] += area(right - left, bottom - top);
			for (int y = top; y < bottom; y++)
			{
				int position = y * width + left;
				for (int x = left; x < right; x++)
				{
					overlapCoverage[position++]++;
				}
			}
		}
		lap(Phase.COVERAGE_BUILD);
		if (witnessBuffers.length < candidates.size())
		{
			long allocStart = System.nanoTime();
			witnessBuffers = new int[candidates.size()][];
			sharedBuffers = new int[candidates.size()][];
			allocated(allocStart, 0);
		}
		int[] counts = new int[candidates.size()];
		int[] changes = new int[candidates.size()];
		int[] sharedCounts = new int[candidates.size()];
		int[] sharedChanges = new int[candidates.size()];
		boolean[] dormant = new boolean[candidates.size()];
		boolean complex = false;
		long candidateArea = 0;
		long allocatedBytesMin = 20L * candidates.size() + coverageAllocation;
		// Level 4: every candidate's exclusive (and shared) positions live contiguously in one
		// retained pool, candidate i at [from[i], from[i] + count[i]). Levels 0-3 keep one
		// buffer per candidate index, each the candidate's full visible area, at offset 0.
		boolean pooled = optimisation >= 4;
		int[] exclusiveFrom = new int[candidates.size()];
		int[] sharedFrom = new int[candidates.size()];
		int exclusiveUsed = 0;
		int sharedUsed = 0;
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
			if (!pooled && (positions == null || positions.length < child.width * child.height))
			{
				long allocStart = System.nanoTime();
				positions = new int[child.width * child.height];
				witnessBuffers[i] = positions;
				allocated(allocStart, 4L * positions.length);
				allocatedBytesMin += 4L * positions.length;
			}
			if (pooled)
			{
				// Reserve this candidate's upper bound (every position it scans) before scanning, so
				// the inner loop has no call and works on locals. The pool then holds the actual
				// exclusive positions so far (at most one per pixel) plus one candidate's area.
				long bound = 0;
				for (int y = child.y + MARGIN; y < child.y + child.height - MARGIN; y++)
				{
					int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
						child.x + MARGIN, child.x + child.width - MARGIN, segments);
					for (int s = 0; s < parts; s++)
					{
						bound += segments[2 * s + 1] - segments[2 * s];
					}
				}
				if (exclusiveUsed + bound > exclusivePool.length)
				{
					exclusivePool = grow(exclusivePool, exclusiveUsed + bound, 2L * pixels.length);
				}
				int[] pool = exclusivePool;
				short[] coverage = overlapCoverage;
				int from = exclusiveUsed;
				int n = from;
				for (int y = child.y + MARGIN; y < child.y + child.height - MARGIN; y++)
				{
					int row = y * width;
					int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
						child.x + MARGIN, child.x + child.width - MARGIN, segments);
					for (int s = 0; s < parts; s++)
					{
						int to = segments[2 * s + 1];
						for (int x = segments[2 * s]; x < to; x++)
						{
							if (coverage[row + x] == 1)
							{
								pool[n++] = row + x;
							}
						}
					}
				}
				exclusiveUsed = n;
				exclusiveFrom[i] = from;
				counts[i] = n - from;
				counters[Counter.WITNESS_SCANNED.ordinal()] += bound;
			}
			else if (optimisation >= 3)
			{
				// Level 3: the same positions in the same order, skipping the root per row.
				long scanned = 0;
				int n = 0;
				for (int y = child.y + MARGIN; y < child.y + child.height - MARGIN; y++)
				{
					int row = y * width;
					int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
						child.x + MARGIN, child.x + child.width - MARGIN, segments);
					for (int s = 0; s < parts; s++)
					{
						int to = segments[2 * s + 1];
						scanned += to - segments[2 * s];
						for (int x = segments[2 * s]; x < to; x++)
						{
							if (overlapCoverage[row + x] == 1)
							{
								positions[n++] = row + x;
							}
						}
					}
				}
				counts[i] = n;
				counters[Counter.WITNESS_SCANNED.ordinal()] += scanned;
			}
			else
			{
				counters[Counter.WITNESS_SCANNED.ordinal()] += area(child.width - 2 * MARGIN, child.height - 2 * MARGIN);
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
			}
			if (counts[i] == 0)
			{
				complex = true;
				// Overlapping siblings can have no exclusive pixels. Sample their visible
				// area outside the root to prove a dormant child was not drawn.
				int[] shared = sharedBuffers[i];
				if (!pooled && (shared == null || shared.length < child.width * child.height))
				{
					long allocStart = System.nanoTime();
					shared = new int[child.width * child.height];
					sharedBuffers[i] = shared;
					allocated(allocStart, 4L * shared.length);
					allocatedBytesMin += 4L * shared.length;
				}
				int insetX = Math.min(MARGIN, (child.width - 1) / 2);
				int insetY = Math.min(MARGIN, (child.height - 1) / 2);
				if (pooled)
				{
					// Count first (per row, from the segment ends), reserve once, then fill.
					long needed = 0;
					for (int y = child.y + insetY; y < child.y + child.height - insetY; y++)
					{
						int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
							child.x + insetX, child.x + child.width - insetX, segments);
						for (int s = 0; s < parts; s++)
						{
							needed += segments[2 * s + 1] - segments[2 * s];
						}
					}
					if (sharedUsed + needed > sharedPool.length)
					{
						// Bounded: the visible candidate area is at most MAX_CANDIDATE_AREA_PIXELS.
						sharedPool = grow(sharedPool, sharedUsed + needed, MAX_CANDIDATE_AREA_PIXELS);
					}
					int[] pool = sharedPool;
					int from = sharedUsed;
					int n = from;
					for (int y = child.y + insetY; y < child.y + child.height - insetY; y++)
					{
						int row = y * width;
						int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
							child.x + insetX, child.x + child.width - insetX, segments);
						for (int s = 0; s < parts; s++)
						{
							int to = segments[2 * s + 1];
							for (int x = segments[2 * s]; x < to; x++)
							{
								pool[n++] = row + x;
							}
						}
					}
					sharedUsed = n;
					sharedFrom[i] = from;
					sharedCounts[i] = n - from;
					counters[Counter.WITNESS_SCANNED.ordinal()] += needed;
				}
				else if (optimisation >= 3)
				{
					long scanned = 0;
					int n = 0;
					for (int y = child.y + insetY; y < child.y + child.height - insetY; y++)
					{
						int row = y * width;
						int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
							child.x + insetX, child.x + child.width - insetX, segments);
						for (int s = 0; s < parts; s++)
						{
							int to = segments[2 * s + 1];
							scanned += to - segments[2 * s];
							for (int x = segments[2 * s]; x < to; x++)
							{
								shared[n++] = row + x;
							}
						}
					}
					sharedCounts[i] = n;
					counters[Counter.WITNESS_SCANNED.ordinal()] += scanned;
				}
				else
				{
					counters[Counter.WITNESS_SCANNED.ordinal()] += area(child.width - 2 * insetX, child.height - 2 * insetY);
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
			}
			candidateArea += (long) child.width * child.height;
		}
		// Candidate i's positions are lists[i][from[i] .. from[i] + count[i]); read only after
		// the scan, so a pool that grew mid-scan is referenced in its final form.
		int[][] exclusiveLists = new int[candidates.size()][];
		int[][] sharedLists = new int[candidates.size()][];
		for (int i = 0; i < candidates.size(); i++)
		{
			exclusiveLists[i] = pooled ? exclusivePool : witnessBuffers[i];
			sharedLists[i] = pooled ? sharedPool : sharedBuffers[i];
		}
		for (int i = 0; i < candidates.size(); i++)
		{
			counters[Counter.EXCLUSIVE_MARKS.ordinal()] += counts[i];
			counters[Counter.SHARED_MARKS.ordinal()] += sharedCounts[i];
			counters[Counter.DORMANT.ordinal()] += dormant[i] ? 1 : 0;
		}
		lap(Phase.WITNESS_SCAN);
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
				long allocStart = System.nanoTime();
				footprintMask = new byte[pixels.length];
				allocated(allocStart, footprintMask.length);
				allocatedBytesMin += footprintMask.length;
			}
			if (footprintPositions.length < capacity)
			{
				// Level 4 grows by half again (to at most one position per pixel), so a run of
				// slightly larger menus does not reallocate on each one.
				int grown = pooled ? (int) Math.min(pixels.length,
					Math.max(capacity, footprintPositions.length + (long) footprintPositions.length / 2)) : capacity;
				long allocStart = System.nanoTime();
				footprintPositions = new int[grown];
				changedFootprint = new int[grown];
				allocated(allocStart, 8L * grown);
				allocatedBytesMin += 8L * grown;
			}
		}

		if (snapshot.length != pixels.length)
		{
			long allocStart = System.nanoTime();
			snapshot = new int[pixels.length];
			allocated(allocStart, 4L * snapshot.length);
			allocatedBytesMin += 4L * snapshot.length;
		}
		lap(Phase.FOOTPRINT_SETUP);
		phaseTimes[0] = System.nanoTime() - start;
		int[] before = snapshot;
		long copyStart = System.nanoTime();
		System.arraycopy(pixels, 0, before, 0, pixels.length);
		phaseTimes[1] = System.nanoTime() - copyStart;
		lap(Phase.SNAPSHOT);
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
			counters[Counter.ROOT_WITNESS.ordinal()] = rootWitness;
			lap(Phase.MARK_ROOT);
			// Level 1: a shared sample is candidate i's inset visible area outside the root, the
			// exact region the footprint marking below writes for every non-dormant candidate, so
			// when footprint marking runs these writes are repeated before the native draw.
			boolean writeShared = optimisation < 1 || !footprintEnabled;
			for (int i = 0; i < candidates.size(); i++)
			{
				int[] exclusive = exclusiveLists[i];
				for (int j = exclusiveFrom[i], end = j + counts[i]; j < end; j++)
				{
					pixels[exclusive[j]] = SENTINEL;
				}
				if (writeShared)
				{
					int[] shared = sharedLists[i];
					for (int j = sharedFrom[i], end = j + sharedCounts[i]; j < end; j++)
					{
						pixels[shared[j]] = SENTINEL;
					}
					counters[Counter.SHARED_WRITES.ordinal()] += sharedCounts[i];
				}
			}
			lap(Phase.MARK_WITNESS);
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
					if (optimisation >= 3)
					{
						// Level 3: per row, the visible area and bounds come from the segment
						// ends (the per-pixel min/max of the same x and y values); marking and
						// its order are unchanged.
						long scanned = 0;
						int area = 0;
						int minX = visibleMinX[i];
						int minY = visibleMinY[i];
						int maxX = visibleMaxX[i];
						int maxY = visibleMaxY[i];
						for (int y = child.y + insetY; y < child.y + child.height - insetY; y++)
						{
							int row = y * width;
							int parts = ExactCoverageResolver.outsideRoot(root, MARGIN, y,
								child.x + insetX, child.x + child.width - insetX, segments);
							for (int s = 0; s < parts; s++)
							{
								int from = segments[2 * s];
								int to = segments[2 * s + 1];
								scanned += to - from;
								area += to - from;
								minX = Math.min(minX, from);
								maxX = Math.max(maxX, to - 1);
								minY = Math.min(minY, y);
								maxY = Math.max(maxY, y);
								for (int x = from; x < to; x++)
								{
									int position = row + x;
									if (footprintMask[position] == 0)
									{
										footprintMask[position] = 1;
										footprintPositions[footprintCount++] = position;
										pixels[position] = SENTINEL;
									}
								}
							}
						}
						visibleArea[i] += area;
						visibleMinX[i] = minX;
						visibleMinY[i] = minY;
						visibleMaxX[i] = maxX;
						visibleMaxY[i] = maxY;
						counters[Counter.FOOTPRINT_SCANNED.ordinal()] += scanned;
						continue;
					}
					counters[Counter.FOOTPRINT_SCANNED.ordinal()] += area(child.width - 2 * insetX, child.height - 2 * insetY);
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
			counters[Counter.FOOTPRINT_MARKS.ordinal()] = footprintCount;
			lap(Phase.MARK_FOOTPRINT);
			phaseTimes[2] = System.nanoTime() - markerStart;
			Observer watching = observer;
			if (watching != null)
			{
				watching.beforeNativeDraw(pixels, width, height);
				lap(Phase.MARK_FOOTPRINT);
			}
			long drawStart = System.nanoTime();
			nativeDraw.run();
			drawNanos = System.nanoTime() - drawStart;
			lap(Phase.NATIVE_DRAW);
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
			lap(Phase.COMPARE_ROOT);
			for (int i = 0; i < candidates.size(); i++)
			{
				int[] exclusive = exclusiveLists[i];
				for (int j = exclusiveFrom[i], end = j + counts[i]; j < end; j++)
				{
					if (pixels[exclusive[j]] != SENTINEL)
					{
						changes[i]++;
					}
				}
				int[] shared = sharedLists[i];
				for (int j = sharedFrom[i], end = j + sharedCounts[i]; j < end; j++)
				{
					if (pixels[shared[j]] != SENTINEL)
					{
						sharedChanges[i]++;
					}
				}
			}
			lap(Phase.COMPARE_WITNESS);
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
			counters[Counter.FOOTPRINT_CHANGED.ordinal()] = footprintChanged;
			lap(Phase.COMPARE_FOOTPRINT);
			phaseTimes[3] = System.nanoTime() - compareStart;
			if ((EXACT_COVERAGE_SHADOW || exactRequired) && rootChanged > 0)
			{
				// Reads the marked pixels before the restore; it cannot throw or write pixels.
				int[][] positions;
				int[] sizes;
				if (optimisation >= 2 && footprintEnabled)
				{
					// Level 2: with footprint marking on, the footprint list already holds every
					// marked position outside the root, each once. For a non-dormant candidate its
					// exclusive positions lie in its MARGIN-inset area (inset >= the footprint's
					// min(MARGIN, (size - 1) / 2)), and its shared positions are exactly its
					// footprint area; dormant candidates mark nothing. The resolver reads its input
					// only as a set, so the outcome is the same. ProbeLevelEquivalenceTest checks
					// the sets on every pass of the corpora.
					positions = new int[][]{footprintPositions};
					sizes = new int[]{footprintCount};
				}
				else if (pooled)
				{
					// Level 4: the pools hold the same per-candidate lists back to back.
					positions = new int[][]{exclusivePool, sharedPool, footprintPositions};
					sizes = new int[]{exclusiveUsed, sharedUsed, footprintCount};
				}
				else
				{
					int lists = 2 * candidates.size() + 1;
					positions = new int[lists][];
					sizes = new int[lists];
					for (int i = 0; i < candidates.size(); i++)
					{
						positions[2 * i] = witnessBuffers[i];
						sizes[2 * i] = counts[i];
						positions[2 * i + 1] = sharedBuffers[i];
						sizes[2 * i + 1] = sharedCounts[i];
					}
					positions[lists - 1] = footprintPositions;
					sizes[lists - 1] = footprintCount;
				}
				exactResolver.rowClip(optimisation >= 3);
				Observer watchingResolver = observer;
				if (watchingResolver != null)
				{
					watchingResolver.resolverInput(positions, sizes);
				}
				lastExact = exactResolver.resolve(pixels, width, height, SENTINEL, root, MARGIN,
					candidates, tree.candidateParents, dormant, positions, sizes);
				counters[Counter.EXACT_CHANGED.ordinal()] = lastExact.changedPixels;
				for (int size : sizes)
				{
					counters[Counter.RESOLVER_POSITIONS.ordinal()] += size;
				}
				counters[Counter.RESOLVER_SCANNED.ordinal()] = exactResolver.lastScanned();
				counters[Counter.RESOLVER_CLEARED.ordinal()] = exactResolver.lastCleared();
			}
			lap(Phase.EXACT_RESOLVE);
		}
		finally
		{
			long restoreStart = System.nanoTime();
			System.arraycopy(before, 0, pixels, 0, before.length);
			lap(Phase.RESTORE_COPY);
			for (int i = 0; i < footprintCount; i++)
			{
				footprintMask[footprintPositions[i]] = 0;
			}
			lap(Phase.RESTORE_MASK);
			restored = Arrays.equals(before, pixels);
			lap(Phase.RESTORE_VERIFY);
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
		lap(Phase.GEOMETRY_CHECK);
		if (!restored || rootWitness == 0 || rootChanged == 0)
		{
			return Result.afterDraw(!restored ? State.FAILURE
				: rootWitness == 0 ? State.AMBIGUOUS : State.FAILURE,
				!restored ? "framebuffer restoration failed"
					: rootWitness == 0 ? "ambiguous root geometry" : "native draw missed buffer",
				start, counts, changes, sharedCounts, sharedChanges,
				drawNanos, before.length, allocatedBytesMin, restored);
		}
		// Restored exactly, every rectangle unchanged by the draw, and the draw reached the
		// marked root: the evidence describes this frame. Every result below is a reading of it.
		lastFramebufferVerified = true;
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

	private void lap(Phase phase)
	{
		lap(phase, System.nanoTime());
	}

	/** Closes {@code phase} at {@code now}, excluding allocation time, which is reported as ALLOCATE. */
	private void lap(Phase phase, long now)
	{
		phaseNanos[phase.ordinal()] += Math.max(0, now - lapMark - (allocNanos - allocAtLap));
		lapMark = now;
		allocAtLap = allocNanos;
	}

	private void allocated(long allocStart, long bytes)
	{
		allocNanos += System.nanoTime() - allocStart;
		counters[Counter.ALLOCATED_BYTES.ordinal()] += bytes;
		counters[Counter.ALLOCATIONS.ordinal()]++;
	}

	/** Level 4 pool growth: at least {@code needed}, otherwise doubling, never past {@code cap}. */
	private int[] grow(int[] pool, long needed, long cap)
	{
		long size = Math.max(needed, Math.min(cap, Math.max(4096L, 2L * pool.length)));
		long allocStart = System.nanoTime();
		int[] grown = Arrays.copyOf(pool, (int) size);
		allocated(allocStart, 4L * size);
		return grown;
	}

	private static long area(int width, int height)
	{
		return width <= 0 || height <= 0 ? 0 : (long) width * height;
	}

	/**
	 * Exclusive phases in probe order; together with ALLOCATE they sum to the whole pass.
	 * DECISION is everything after the last lap: chain and footprint resolution and early exits.
	 */
	enum Phase
	{
		VALIDATE, COVERAGE_CLEAR, COVERAGE_BUILD, WITNESS_SCAN, FOOTPRINT_SETUP, SNAPSHOT,
		MARK_ROOT, MARK_WITNESS, MARK_FOOTPRINT, NATIVE_DRAW, COMPARE_ROOT, COMPARE_WITNESS,
		COMPARE_FOOTPRINT, EXACT_RESOLVE, RESTORE_COPY, RESTORE_MASK, RESTORE_VERIFY,
		GEOMETRY_CHECK, DECISION, ALLOCATE
	}

	/** Work done by one pass, in pixels (positions) unless named otherwise. */
	enum Counter
	{
		FRAME_PIXELS, CANDIDATES, VISIBLE_CANDIDATES, DORMANT, ROOT_PIXELS, ROOT_WITNESS,
		COVERAGE_CLEARED, COVERAGE_WRITES, WITNESS_SCANNED, EXCLUSIVE_MARKS, SHARED_MARKS,
		FOOTPRINT_SCANNED, FOOTPRINT_MARKS, FOOTPRINT_CHANGED, EXACT_CHANGED,
		ALLOCATIONS, ALLOCATED_BYTES,
		/** Shared positions actually written with the sentinel (all of SHARED_MARKS at level 0). */
		SHARED_WRITES,
		/** Positions handed to the exact resolver, duplicates included. */
		RESOLVER_POSITIONS,
		/** Pixels the exact resolver's coverage scan visited. */
		RESOLVER_SCANNED,
		/** Positions the exact resolver's mask clear visited. */
		RESOLVER_CLEARED,
		/** Every sentinel write: root, exclusive, shared and footprint. */
		SENTINEL_WRITES,
		/** Every per-pixel iteration of the probe's own loops; bulk full-frame copies are FRAME_PIXELS. */
		PIXELS_VISITED
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
		int visibleCandidates;
		long candidatePixels;
		long preparationNanos;
		long copyNanos;
		long markerNanos;
		long comparisonNanos;
		long restoreNanos;
		long decisionNanos;
		int footprintMatches;
		int footprintChangedPixels;
		Rectangle footprintBounds;
		ExactCoverageResolver.Outcome exact = ExactCoverageResolver.Outcome.NOT_EVALUATED;
		/** Diagnostic: nanoseconds per {@link Phase} and work per {@link Counter}; never decides. */
		long[] phaseNanos = new long[Phase.values().length];
		long[] counters = new long[Counter.values().length];
		/** The whole pass as the phases measure it, including the work before {@link #nanoseconds} starts. */
		long passNanos;

		long phase(Phase phase)
		{
			return phaseNanos[phase.ordinal()];
		}

		long count(Counter counter)
		{
			return counters[counter.ordinal()];
		}

		/** Nonzero phases in microseconds, for logs. */
		String phaseSummary()
		{
			StringBuilder out = new StringBuilder();
			for (Phase phase : Phase.values())
			{
				long nanos = phaseNanos[phase.ordinal()];
				if (nanos >= 1_000)
				{
					out.append(out.length() == 0 ? "" : " ").append(phase).append('=').append(nanos / 1_000);
				}
			}
			return out.toString();
		}

		/** Nonzero counters, for logs. */
		String counterSummary()
		{
			StringBuilder out = new StringBuilder();
			for (Counter counter : Counter.values())
			{
				long value = counters[counter.ordinal()];
				if (value != 0)
				{
					out.append(out.length() == 0 ? "" : " ").append(counter).append('=').append(value);
				}
			}
			return out.toString();
		}

		/**
		 * The framebuffer was restored exactly, no menu rectangle changed during the draw, and
		 * the native draw reached the marked root. False for every buffer, restore and geometry
		 * failure, including a later buffer identity failure, which builds a fresh result.
		 */
		boolean framebufferVerified;

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
