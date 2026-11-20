package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.runelite.api.MenuEntry;

/**
 * Tells apart child menus that share one rectangle by what the native draw itself put in it.
 *
 * <p>The probe already proves that exactly one of several one-menu chains was drawn (exact
 * AMBIGUOUS, every explaining chain listed, none able to hide a grandchild), and that each
 * fills its whole rectangle and draws nothing else outside the root. Those chains differ only
 * in which menu native filled the shared rectangle with. Native draws a menu as a fixed
 * function of its size, its opener's text and each row's option, target and arrow, plus the
 * pointer's row highlight, which only turns a row's base text colour from white to yellow.
 * So a menu's native pixels, read in one frame where the probe proved it alone was drawn, are
 * what native draws for those contents in any later frame, except for yellow.
 *
 * <p>Each such image is remembered by those contents (never by position, entry identity or
 * hash). In an ambiguous frame, a remembered alternative whose image differs from the
 * captured pixels anywhere neither is yellow, while its header (identical by construction
 * among the alternatives) matches exactly, was not drawn. When every alternative but one is
 * excluded that way, and the remaining one matches its own image or has none, the remaining
 * one is the menu native drew. Anything short of that is inconclusive and stays native.
 */
final class NativeChildImages
{
	/** Opaque yellow: the native hover colour of a row's base text. */
	static final int HOVER_TEXT = 0xFFFF00;
	/** The native header bar and its text end above the first row's text. */
	static final int HEADER_BAND = 17;
	private static final int MAX_IMAGES = 16;
	private static final long MAX_STORED_PIXELS = 262_144;

	private final LinkedHashMap<Contents, int[]> images = new LinkedHashMap<>();
	private long storedPixels;

	void clear()
	{
		images.clear();
		storedPixels = 0;
	}

	int size()
	{
		return images.size();
	}

	/**
	 * Remembers candidate {@code candidate}'s native pixels from a frame where it alone was
	 * drawn in its rectangle: the exact reading resolved it, or {@link #identify} did.
	 */
	void remember(NativeSubmenuProbe.Result result, SubmenuTree tree, int candidate)
	{
		NativeSubmenuProbe.NativeCapture capture = result.framebufferVerified ? result.nativeCapture : null;
		int k = capture == null ? -1 : capture.indexOf(candidate);
		if (k < 0 || eligibility(tree, candidate, capture, k) != null)
		{
			return;
		}
		Rectangle r = capture.bounds(k);
		int[] image = new int[r.width * r.height];
		for (int dy = 0; dy < r.height; dy++)
		{
			for (int dx = 0; dx < r.width; dx++)
			{
				image[dy * r.width + dx] = capture.pixel(k, dx, dy) & 0xFFFFFF;
			}
		}
		Contents key = Contents.of(tree, candidate);
		int[] previous = images.remove(key);
		if (previous != null)
		{
			storedPixels -= previous.length;
		}
		images.put(key, image);
		storedPixels += image.length;
		Iterator<Map.Entry<Contents, int[]>> eldest = images.entrySet().iterator();
		while ((images.size() > MAX_IMAGES || storedPixels > MAX_STORED_PIXELS) && eldest.hasNext())
		{
			int[] dropped = eldest.next().getValue();
			if (dropped == image)
			{
				break;
			}
			storedPixels -= dropped.length;
			eldest.remove();
		}
	}

	/**
	 * The one alternative of an ambiguous frame that native drew, decided only by the native
	 * pixels; null when that is not proven.
	 *
	 * @param why diagnostics only: receives the reason when the result is null; may be null
	 */
	int[] identify(NativeSubmenuProbe.Result result, SubmenuTree tree, StringBuilder why)
	{
		ExactCoverageResolver.Outcome exact = result.exact;
		if (!result.framebufferVerified || exact == null
			|| exact.state != ExactCoverageResolver.State.AMBIGUOUS || exact.alternatives.length < 2
			|| exact.alternatives.length != exact.explainingChains)
		{
			return reject(why, "alternatives not exact");
		}
		NativeSubmenuProbe.NativeCapture capture = result.nativeCapture;
		if (capture == null)
		{
			return reject(why, "no native capture");
		}
		int[][] alternatives = exact.alternatives;
		boolean previousProved = false;
		Rectangle shared = null;
		String header = null;
		for (int[] alternative : alternatives)
		{
			if (alternative.length != 1 || alternative[0] < 0
				|| alternative[0] >= tree.candidateParents.size()
				|| tree.candidateParents.get(alternative[0]) != -1)
			{
				return reject(why, "alternative " + Arrays.toString(alternative) + " is not one root child");
			}
			previousProved |= Arrays.equals(alternative, result.activeChain);
			int candidate = alternative[0];
			int k = capture.indexOf(candidate);
			if (k < 0)
			{
				return reject(why, "candidate " + candidate + " not captured");
			}
			String ineligible = eligibility(tree, candidate, capture, k);
			if (ineligible != null)
			{
				return reject(why, "candidate " + candidate + " " + ineligible);
			}
			SubmenuTree.Node node = tree.nodes.get(candidate + 1);
			if (shared == null)
			{
				shared = node.bounds;
				header = Contents.openerHeader(tree, node);
			}
			else if (!shared.equals(node.bounds))
			{
				// Separate rectangles: each capture would also hold the other's pixels.
				return reject(why, "alternatives do not share one rectangle");
			}
			else if (!Objects.equals(header, Contents.openerHeader(tree, node)))
			{
				return reject(why, "alternative headers differ");
			}
		}
		if ((result.state == NativeSubmenuProbe.State.NO_CHILD
			|| result.state == NativeSubmenuProbe.State.ACTIVE) && !previousProved)
		{
			return reject(why, "previous resolver proved " + Arrays.toString(result.activeChain)
				+ ", not an alternative");
		}
		int remaining = -1;
		StringBuilder verdicts = why == null ? null : new StringBuilder();
		for (int i = 0; i < alternatives.length; i++)
		{
			int candidate = alternatives[i][0];
			int k = capture.indexOf(candidate);
			int[] image = images.get(Contents.of(tree, candidate));
			Verdict verdict = image == null ? Verdict.UNKNOWN : compare(capture, k, image);
			if (verdicts != null)
			{
				verdicts.append(verdicts.length() == 0 ? "" : " ").append('#').append(candidate)
					.append('=').append(verdict);
			}
			if (verdict == Verdict.STALE)
			{
				return reject(why, "#" + candidate + " remembered header differs from the native header");
			}
			if (verdict == Verdict.EXCLUDED)
			{
				continue;
			}
			if (remaining >= 0)
			{
				return reject(why, "more than one alternative fits the native pixels: " + verdicts);
			}
			remaining = i;
		}
		if (remaining < 0)
		{
			return reject(why, "no alternative fits the native pixels: " + verdicts);
		}
		return alternatives[remaining];
	}

	enum Verdict
	{
		UNKNOWN, MATCHES, EXCLUDED, STALE
	}

	/**
	 * Yellow is ignored on either side: it is the only colour the pointer changes. The header
	 * is compared exactly first, since every alternative draws the same header.
	 */
	private static Verdict compare(NativeSubmenuProbe.NativeCapture capture, int k, int[] image)
	{
		Rectangle r = capture.bounds(k);
		for (int dy = 0; dy < HEADER_BAND; dy++)
		{
			for (int dx = 0; dx < r.width; dx++)
			{
				if ((capture.pixel(k, dx, dy) & 0xFFFFFF) != image[dy * r.width + dx])
				{
					return Verdict.STALE;
				}
			}
		}
		for (int dy = HEADER_BAND; dy < r.height; dy++)
		{
			for (int dx = 0; dx < r.width; dx++)
			{
				int now = capture.pixel(k, dx, dy) & 0xFFFFFF;
				int then = image[dy * r.width + dx];
				if (now != then && now != HOVER_TEXT && then != HOVER_TEXT)
				{
					return Verdict.EXCLUDED;
				}
			}
		}
		return Verdict.MATCHES;
	}

	/**
	 * Null when the capture is a whole native draw of this menu at its natural height: every
	 * pixel on the canvas and outside the root, none left unwritten, and no submenu scroll.
	 */
	private static String eligibility(SubmenuTree tree, int candidate,
		NativeSubmenuProbe.NativeCapture capture, int k)
	{
		SubmenuTree.Node node = tree.nodes.get(candidate + 1);
		Rectangle r = capture.bounds(k);
		if (!r.equals(node.bounds) || node.entries.length == 0
			|| r.height != MenuRenderer.HEADER_HEIGHT + 3 + node.entries.length * MenuRenderer.ROW_HEIGHT)
		{
			return "not at its natural height";
		}
		for (int dy = 0; dy < r.height; dy++)
		{
			for (int dx = 0; dx < r.width; dx++)
			{
				if (!capture.usable(k, dx, dy))
				{
					return "partly off canvas or over the root";
				}
			}
		}
		return capture.painted(k) ? null : "not wholly drawn";
	}

	private static int[] reject(StringBuilder why, String reason)
	{
		if (why != null)
		{
			why.append(reason);
		}
		return null;
	}

	/** Everything native draws a child menu from, apart from its position and the pointer. */
	static final class Contents
	{
		private final int width;
		private final int height;
		private final String header;
		private final String[] options;
		private final String[] targets;
		private final boolean[] submenus;

		private Contents(int width, int height, String header, String[] options, String[] targets,
			boolean[] submenus)
		{
			this.width = width;
			this.height = height;
			this.header = header;
			this.options = options;
			this.targets = targets;
			this.submenus = submenus;
		}

		static Contents of(SubmenuTree tree, int candidate)
		{
			SubmenuTree.Node node = tree.nodes.get(candidate + 1);
			int count = node.entries.length;
			String[] options = new String[count];
			String[] targets = new String[count];
			boolean[] submenus = new boolean[count];
			for (int i = 0; i < count; i++)
			{
				MenuEntry entry = node.entries[i];
				options[i] = entry == null ? null : entry.getOption();
				targets[i] = entry == null ? null : entry.getTarget();
				submenus[i] = node.childFlags[i];
			}
			return new Contents(node.bounds.width, node.bounds.height, openerHeader(tree, node),
				options, targets, submenus);
		}

		/** The opening entry's target: the text native heads a child menu with. */
		static String openerHeader(SubmenuTree tree, SubmenuTree.Node node)
		{
			MenuEntry opener = tree.nodes.get(node.parentNode).entries[node.parentEntryIndex];
			return opener == null ? null : opener.getTarget();
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Contents))
			{
				return false;
			}
			Contents c = (Contents) o;
			return width == c.width && height == c.height && Objects.equals(header, c.header)
				&& Arrays.equals(options, c.options) && Arrays.equals(targets, c.targets)
				&& Arrays.equals(submenus, c.submenus);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(width, height, header, Arrays.hashCode(options), Arrays.hashCode(targets),
				Arrays.hashCode(submenus));
		}
	}
}
