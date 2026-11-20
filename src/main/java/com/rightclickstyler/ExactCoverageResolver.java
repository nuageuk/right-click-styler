package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.List;

/**
 * Exact-coverage reading of the native probe's pixel evidence. The overlay renders only a chain
 * this reader RESOLVED; the probe's own resolver is a cross-check, and a frame where the two
 * prove different chains stays native.
 *
 * <p>It reads the pixels the probe marked with sentinels, after the native draw and before the
 * restore, and never draws or writes the framebuffer. It relies on two properties of the native
 * menu draw, which the logged traces support: an open menu overwrites every pixel of its own
 * rectangle (A1, so a drawn candidate has all of its marked pixels changed), and nothing is
 * drawn outside the root and the open chain (A2, so every changed marked pixel outside the
 * root lies in a drawn candidate). It applies no tolerance.
 *
 * <p>A candidate is <em>fully covered</em> when its visible rectangle outside the root contains
 * marked pixels and every one of them changed, whichever candidate marked it: a drawn menu
 * changes every marked pixel under it, including the margin band of its own rectangle. A chain is a fully covered candidate together with all of its ancestors, each
 * fully covered; the empty chain is also considered. A chain <em>explains</em> the frame when
 * every changed marked pixel outside the root lies inside one of its rectangles. The result is
 * RESOLVED only when exactly one chain explains the frame and no candidate without evidence
 * could extend it. Two chains with the same visible pixels (identical rectangles, a nested
 * child contained in its parent, a difference clipped off the canvas) stay AMBIGUOUS, and
 * evidence that contradicts A1 or A2 is UNEXPLAINED. Neither is ever a guess. An AMBIGUOUS
 * outcome lists every explaining chain as {@link Outcome#alternatives}, so the overlay can check
 * whether they would all render identically; it still never picks one by identity.
 */
final class ExactCoverageResolver
{
	enum State
	{
		NOT_EVALUATED, RESOLVED, AMBIGUOUS, UNEVIDENCED, UNEXPLAINED, ERROR
	}

	static final class Outcome
	{
		private static final int[][] NO_ALTERNATIVES = new int[0][];
		static final Outcome NOT_EVALUATED = new Outcome(State.NOT_EVALUATED, new int[0], 0, new int[0], 0);

		final State state;
		/** Candidate indexes from the root's child down; empty for root only. */
		final int[] chain;
		final int explainingChains;
		final int fullyCovered;
		/** Evidence only: the fully covered candidate indexes, ascending. Never used to decide. */
		final int[] covered;
		final int changedPixels;
		/**
		 * AMBIGUOUS only: every chain that explains the frame, in ascending leaf order, when no
		 * visible candidate without evidence could extend any of them. Under A1 and A2 the chain
		 * native drew is then one of these. Empty in every other case. It never selects a chain:
		 * the overlay may theme the frame only when all of them would render identically.
		 */
		final int[][] alternatives;
		/**
		 * Evidence only: the visible candidate without evidence that could be drawn beneath an
		 * explaining chain's last menu (why an UNEVIDENCED outcome, or an AMBIGUOUS one without
		 * alternatives, was withheld); -1 when none. Never used to decide.
		 */
		final int hiddenCandidate;

		Outcome(State state, int[] chain, int explainingChains, int[] covered, int changedPixels)
		{
			this(state, chain, explainingChains, covered, changedPixels, NO_ALTERNATIVES, -1);
		}

		Outcome(State state, int[] chain, int explainingChains, int[] covered, int changedPixels,
			int[][] alternatives, int hiddenCandidate)
		{
			this.state = state;
			this.chain = chain;
			this.explainingChains = explainingChains;
			this.covered = covered;
			this.fullyCovered = covered.length;
			this.changedPixels = changedPixels;
			this.alternatives = alternatives;
			this.hiddenCandidate = hiddenCandidate;
		}

		@Override
		public String toString()
		{
			return state + "(chain=" + java.util.Arrays.toString(chain) + " explaining=" + explainingChains
				+ " alternatives=" + alternatives.length + " hidden=" + hiddenCandidate
				+ " fullyCovered=" + fullyCovered + " changed=" + changedPixels + ")";
		}
	}

	private static final byte MARKED = 1;
	private static final byte COLLECTED = 2;
	private byte[] mask = new byte[0];
	private int[] changed = new int[0];
	private final int[] segments = new int[4];

	/** Test hook: the mask is cleared after every resolve. */
	boolean maskClean()
	{
		for (byte b : mask)
		{
			if (b != 0)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * @param marked the sentinel position lists the probe wrote, as {lists[i], counts[i]} pairs
	 */
	Outcome resolve(int[] pixels, int width, int height, int sentinel, Rectangle root, int margin,
		List<Rectangle> candidates, List<Integer> parents, boolean[] dormant,
		int[][] lists, int[] counts)
	{
		if (mask.length != pixels.length)
		{
			mask = new byte[pixels.length];
		}
		Outcome outcome;
		try
		{
			outcome = resolveInternal(pixels, width, height, sentinel, root, margin, candidates,
				parents, dormant, lists, counts);
		}
		catch (RuntimeException e)
		{
			outcome = new Outcome(State.ERROR, new int[0], 0, new int[0], 0);
		}
		finally
		{
			// Never throws into the probe: positions come from the probe's own bounded lists.
			for (int list = 0; list < lists.length; list++)
			{
				int[] positions = lists[list];
				for (int i = 0; positions != null && i < counts[list]; i++)
				{
					int position = positions[i];
					if (position >= 0 && position < mask.length)
					{
						mask[position] = 0;
					}
				}
			}
		}
		return outcome;
	}

	private Outcome resolveInternal(int[] pixels, int width, int height, int sentinel,
		Rectangle root, int margin, List<Rectangle> candidates, List<Integer> parents,
		boolean[] dormant, int[][] lists, int[] counts)
	{
		// Collect every marked position once; the changed ones are the evidence to explain.
		int changedCount = 0;
		for (int list = 0; list < lists.length; list++)
		{
			int[] positions = lists[list];
			for (int i = 0; positions != null && i < counts[list]; i++)
			{
				int position = positions[i];
				if (mask[position] != 0)
				{
					continue;
				}
				mask[position] = MARKED;
				if (pixels[position] != sentinel && !insideRoot(root, margin, position % width, position / width))
				{
					if (changedCount == changed.length)
					{
						changed = java.util.Arrays.copyOf(changed, Math.max(1024, changed.length * 2));
					}
					changed[changedCount++] = position;
					mask[position] = COLLECTED;
				}
			}
		}
		int size = candidates.size();
		boolean[] covered = new boolean[size];
		boolean[] evidenced = new boolean[size];
		int fullyCovered = 0;
		Rectangle canvas = new Rectangle(0, 0, width, height);
		for (int c = 0; c < size; c++)
		{
			if (dormant[c])
			{
				continue;
			}
			// The whole visible rectangle, not the candidate's own inset sample: other
			// candidates' marks inside it are evidence about it too.
			Rectangle visible = candidates.get(c).intersection(canvas);
			boolean any = false;
			boolean all = true;
			// Row segments outside the root: the same pixels in the same order as a per-pixel
			// insideRoot test.
			rows:
			for (int y = visible.y; y < visible.y + visible.height; y++)
			{
				int row = y * width;
				int parts = outsideRoot(root, margin, y, visible.x, visible.x + visible.width, segments);
				for (int s = 0; s < parts; s++)
				{
					int to = segments[2 * s + 1];
					for (int x = segments[2 * s]; x < to; x++)
					{
						byte state = mask[row + x];
						if (state == 0)
						{
							continue;
						}
						any = true;
						if (state != COLLECTED)
						{
							all = false; // A1: a drawn candidate changes every marked pixel
							break rows;
						}
					}
				}
			}
			evidenced[c] = any;
			covered[c] = any && all;
			fullyCovered += covered[c] ? 1 : 0;
		}

		int explaining = 0;
		int[] selected = null;
		// Every explaining chain, kept so an ambiguous frame can report them all.
		java.util.List<int[]> explainingList = null;
		int[] path = new int[size + 1];
		// leaf == -1 is the empty chain: root only.
		for (int leaf = -1; leaf < size; leaf++)
		{
			int depth = 0;
			boolean valid = true;
			for (int node = leaf; node >= 0; node = parents.get(node))
			{
				if (!covered[node] || depth > size)
				{
					valid = false;
					break;
				}
				path[depth++] = node;
			}
			if (!valid || !explains(path, depth, candidates, width, changedCount))
			{
				continue;
			}
			int[] found = new int[depth];
			for (int i = 0; i < depth; i++)
			{
				found[i] = path[depth - 1 - i];
			}
			if (++explaining == 1)
			{
				selected = found;
			}
			else
			{
				if (explainingList == null)
				{
					explainingList = new java.util.ArrayList<>();
					explainingList.add(selected);
				}
				explainingList.add(found);
			}
		}
		int[] coveredIndexes = new int[fullyCovered];
		for (int c = 0, n = 0; c < size; c++)
		{
			if (covered[c])
			{
				coveredIndexes[n++] = c;
			}
		}
		if (explaining == 0)
		{
			return new Outcome(State.UNEXPLAINED, new int[0], 0, coveredIndexes, changedCount);
		}
		if (explaining > 1)
		{
			// The alternatives are complete only when none could hide an unevidenced child.
			for (int[] alternative : explainingList)
			{
				int hidden = hiddenWithoutEvidence(alternative, size, dormant, evidenced, parents);
				if (hidden >= 0)
				{
					return new Outcome(State.AMBIGUOUS, new int[0], explaining, coveredIndexes, changedCount,
						Outcome.NO_ALTERNATIVES, hidden);
				}
			}
			return new Outcome(State.AMBIGUOUS, new int[0], explaining, coveredIndexes, changedCount,
				explainingList.toArray(new int[0][]), -1);
		}
		int hidden = hiddenWithoutEvidence(selected, size, dormant, evidenced, parents);
		if (hidden >= 0)
		{
			return new Outcome(State.UNEVIDENCED, new int[0], explaining, coveredIndexes, changedCount,
				Outcome.NO_ALTERNATIVES, hidden);
		}
		return new Outcome(State.RESOLVED, selected, explaining, coveredIndexes, changedCount);
	}

	/**
	 * A visible candidate with no marked pixels outside the root could be drawn beneath the
	 * chain's last menu without leaving evidence.
	 *
	 * @return the first such candidate, or -1
	 */
	private static int hiddenWithoutEvidence(int[] chain, int size, boolean[] dormant,
		boolean[] evidenced, List<Integer> parents)
	{
		int last = chain.length == 0 ? -1 : chain[chain.length - 1];
		for (int c = 0; c < size; c++)
		{
			if (!dormant[c] && !evidenced[c] && parents.get(c) == last)
			{
				return c;
			}
		}
		return -1;
	}

	/** A2: every changed marked pixel outside the root lies inside a chain rectangle. */
	private boolean explains(int[] path, int depth, List<Rectangle> candidates, int width, int changedCount)
	{
		for (int i = 0; i < changedCount; i++)
		{
			int x = changed[i] % width;
			int y = changed[i] / width;
			boolean inside = false;
			for (int d = 0; d < depth && !inside; d++)
			{
				inside = candidates.get(path[d]).contains(x, y);
			}
			if (!inside)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Row form of {@link #insideRoot}: writes the parts of row {@code y}'s half-open range
	 * {@code [from, to)} that lie outside the root's margin box into {@code segments} as
	 * {start0, end0, start1, end1}, in ascending x, and returns how many there are (0-2).
	 * Exactly the x in the range for which {@code insideRoot(root, margin, x, y)} is false: the
	 * same int arithmetic, and an empty margin box (a clipped root whose intersection has a
	 * negative size the margin does not reach past) excludes nothing.
	 */
	static int outsideRoot(Rectangle root, int margin, int y, int from, int to, int[] segments)
	{
		if (from >= to)
		{
			return 0;
		}
		int left = root.x - margin;
		int right = root.x + root.width + margin;
		if (y < root.y - margin || y >= root.y + root.height + margin || left >= right)
		{
			segments[0] = from;
			segments[1] = to;
			return 1;
		}
		int n = 0;
		int end = Math.min(to, left);
		if (from < end)
		{
			segments[0] = from;
			segments[1] = end;
			n = 1;
		}
		int start = Math.max(from, right);
		if (start < to)
		{
			segments[2 * n] = start;
			segments[2 * n + 1] = to;
			n++;
		}
		return n;
	}

	static boolean insideRoot(Rectangle root, int margin, int x, int y)
	{
		return x >= root.x - margin && x < root.x + root.width + margin
			&& y >= root.y - margin && y < root.y + root.height + margin;
	}
}
