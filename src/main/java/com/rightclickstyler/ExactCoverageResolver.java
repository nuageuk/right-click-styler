package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.List;

/**
 * Experimental exact-coverage reading of the native probe's pixel evidence. By default it is a
 * shadow and the probe's existing resolver decides every frame; under the opt-in
 * {@code rightclickstyler.exactCoverageAuthoritative} flag the overlay renders only a chain this
 * reader RESOLVED, and the existing resolver becomes the shadow comparator.
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
 * evidence that contradicts A1 or A2 is UNEXPLAINED. Neither is ever a guess.
 */
final class ExactCoverageResolver
{
	enum State
	{
		NOT_EVALUATED, RESOLVED, AMBIGUOUS, UNEVIDENCED, UNEXPLAINED, ERROR
	}

	static final class Outcome
	{
		static final Outcome NOT_EVALUATED = new Outcome(State.NOT_EVALUATED, new int[0], 0, new int[0], 0);

		final State state;
		/** Candidate indexes from the root's child down; empty for root only. */
		final int[] chain;
		final int explainingChains;
		final int fullyCovered;
		/** Evidence only: the fully covered candidate indexes, ascending. Never used to decide. */
		final int[] covered;
		long nanos;
		final int changedPixels;

		Outcome(State state, int[] chain, int explainingChains, int[] covered, int changedPixels)
		{
			this.state = state;
			this.chain = chain;
			this.explainingChains = explainingChains;
			this.covered = covered;
			this.fullyCovered = covered.length;
			this.changedPixels = changedPixels;
		}

		@Override
		public String toString()
		{
			return state + "(chain=" + java.util.Arrays.toString(chain) + " explaining=" + explainingChains
				+ " fullyCovered=" + fullyCovered + " changed=" + changedPixels + ")";
		}
	}

	private static final byte MARKED = 1;
	private static final byte COLLECTED = 2;
	private byte[] mask = new byte[0];
	private int[] changed = new int[0];

	/**
	 * @param marked the sentinel position lists the probe wrote, as {lists[i], counts[i]} pairs
	 */
	Outcome resolve(int[] pixels, int width, int height, int sentinel, Rectangle root, int margin,
		List<Rectangle> candidates, List<Integer> parents, boolean[] dormant,
		int[][] lists, int[] counts)
	{
		long start = System.nanoTime();
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
		outcome.nanos = System.nanoTime() - start;
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
			for (int y = visible.y; all && y < visible.y + visible.height; y++)
			{
				int row = y * width;
				for (int x = visible.x; x < visible.x + visible.width; x++)
				{
					if (mask[row + x] == 0 || insideRoot(root, margin, x, y))
					{
						continue;
					}
					any = true;
					if (mask[row + x] != COLLECTED)
					{
						all = false; // A1: a drawn candidate changes every marked pixel
						break;
					}
				}
			}
			evidenced[c] = any;
			covered[c] = any && all;
			fullyCovered += covered[c] ? 1 : 0;
		}

		int explaining = 0;
		int[] selected = null;
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
			if (++explaining == 1)
			{
				selected = new int[depth];
				for (int i = 0; i < depth; i++)
				{
					selected[i] = path[depth - 1 - i];
				}
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
			return new Outcome(State.AMBIGUOUS, new int[0], explaining, coveredIndexes, changedCount);
		}
		// A visible candidate with no marked pixels outside the root could be drawn beneath the
		// chain's last menu without leaving evidence.
		int last = selected.length == 0 ? -1 : selected[selected.length - 1];
		for (int c = 0; c < size; c++)
		{
			if (!dormant[c] && !evidenced[c] && parents.get(c) == last)
			{
				return new Outcome(State.UNEVIDENCED, new int[0], explaining, coveredIndexes, changedCount);
			}
		}
		return new Outcome(State.RESOLVED, selected, explaining, coveredIndexes, changedCount);
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

	private static boolean insideRoot(Rectangle root, int margin, int x, int y)
	{
		return x >= root.x - margin && x < root.x + root.width + margin
			&& y >= root.y - margin && y < root.y + root.height + margin;
	}
}
