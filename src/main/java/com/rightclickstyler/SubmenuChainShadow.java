package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.Arrays;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;

/**
 * Shadow validation for reusing a proven submenu chain. Diagnostic only: decisions are
 * compared with the authoritative native probe of the same frame and never reach rendering.
 *
 * <h2>Native state versus the live mouse</h2>
 * The open submenu chain is native client state. It is decided from the client's per-cycle
 * mouse snapshot and its own open delay (a deadline compared against the game cycle), and the
 * native menu draw only reads it. {@code getMouseCanvasPosition} follows the AWT thread live,
 * so a frame's mouse sample is evidence about where native may move next, never about what
 * native has open now. Only a native probe proves the chain.
 *
 * <h2>Proof</h2>
 * A proof is a probe result that passed every production check up to theming: NO_CHILD or
 * ACTIVE, scroll unchanged during the probe and the scrolled-child validation. Ambiguous or
 * rejected probes never create or confirm a proof. The shadow simulates the cache it
 * evaluates: a frame the policy would have reused does not refresh the proof, so the proof's
 * age is what a production cache would have.
 *
 * <h2>Invalidation: the proof is discarded</h2>
 * <ul>
 * <li>Menu opened (RuneLite reuses the root Menu object across openings, so root identity
 * cannot detect a reopen), menu not open, the event already consumed by another subscriber, a menu without submenus or scroll.</li>
 * <li>Root identity, canvas size, scrollability or scroll offset changed.</li>
 * <li>Any change in tree shape or geometry: node count, menu identity, parent, parent entry or
 * bounds of any node, including closed children. Opening a child repositions it.</li>
 * <li>Any entry mutation in the root or the proven chain (every field PreparedMenuState
 * compares, including submenu links), or a broken chain link.</li>
 * <li>Plugin restart, a prototype failure, or an exception in the shadow itself.</li>
 * </ul>
 * Theme and configuration changes do not affect native state and do not invalidate a chain
 * proof. A future image cache must still key on them; this class caches no image.
 *
 * <h2>Reuse rules, evaluated only while the proof is valid</h2>
 * <ol>
 * <li><b>Same cycle:</b> {@code getGameCycle()} equals the proof's cycle. Hypothesis H1: the
 * native chain only changes when the client cycle advances. The mouse is irrelevant here.</li>
 * <li><b>Settled:</b> the cycle has advanced, and for the proof frame and every frame since:
 * the frame's mouse sample hovers the same node and row, and that hover is settled (native
 * has no pending switch for it: the pointer is inside the proven child, on the proven child's
 * parent row, on a plain row of the deepest proven menu, or outside the menu's rows); and no
 * more than {@link #MAX_SETTLED_REUSE_CYCLES} cycles have passed. A hovered row whose child is
 * not the proven one means native's delayed open or close may fire on a later cycle, so it is
 * never settled, however long the mouse stays still. The cycle bound means an unchanged
 * pointer alone cannot extend reuse indefinitely.</li>
 * </ol>
 * Anything else requires a probe.
 */
final class SubmenuChainShadow
{
	/** About 100ms at the client's 50 cycles per second. Provisional; the shadow measures it. */
	static final int MAX_SETTLED_REUSE_CYCLES = 5;

	enum Kind
	{
		PROBE, REUSE_SAME_CYCLE, REUSE_SETTLED
	}

	enum ProbeReason
	{
		NO_PROOF, ROOT, CANVAS, SCROLL, GEOMETRY, STRUCTURE, CYCLE_BACKWARDS,
		UNSETTLED, HOVER_CHANGED, WINDOW_EXPIRED
	}

	enum Verdict
	{
		/** The policy probed; nothing to verify. */
		NONE,
		MATCH,
		MISMATCH,
		/** The authoritative probe was not a proof, so the reuse decision is unverified. */
		UNVERIFIED
	}

	/** One frame's public menu state plus the frame's single mouse sample. */
	static final class Frame
	{
		final Menu root;
		final SubmenuTree tree;
		final ScrollableMenuViewport viewport;
		final boolean scrollable;
		final int scroll;
		final int canvasWidth;
		final int canvasHeight;
		final int cycle;
		final Point mouse;

		Frame(Menu root, SubmenuTree tree, ScrollableMenuViewport viewport, boolean scrollable,
			int scroll, int canvasWidth, int canvasHeight, int cycle, Point mouse)
		{
			this.root = root;
			this.tree = tree;
			this.viewport = viewport;
			this.scrollable = scrollable;
			this.scroll = scroll;
			this.canvasWidth = canvasWidth;
			this.canvasHeight = canvasHeight;
			this.cycle = cycle;
			this.mouse = mouse;
		}
	}

	static final class Decision
	{
		final Kind kind;
		final ProbeReason reason;
		final String detail;
		/** The chain the policy would render, by menu identity; null when it probes. */
		final Menu[] chain;
		final int age;

		private Decision(Kind kind, ProbeReason reason, String detail, Menu[] chain, int age)
		{
			this.kind = kind;
			this.reason = reason;
			this.detail = detail;
			this.chain = chain;
			this.age = age;
		}

		@Override
		public String toString()
		{
			return kind == Kind.PROBE ? "PROBE(" + reason + (detail == null ? "" : ": " + detail) + ")"
				: kind + "(age=" + age + ")";
		}
	}

	private boolean hasProof;
	private SubmenuTree proofTree;
	private PreparedMenuState proofState;
	private int[] proofNodes = new int[0];
	private Menu[] proofChain = new Menu[0];
	private ScrollableMenuViewport proofViewport;
	private boolean proofScrollable;
	private int proofScroll;
	private int proofCanvasWidth;
	private int proofCanvasHeight;
	private int proofCycle;
	private long proofHover;
	private boolean settledSinceProof;
	private boolean hoverStableSinceProof;
	private Decision lastDecision;
	private Verdict lastVerdict = Verdict.NONE;

	/** RuneLite reuses the root Menu across openings, so every opening must end the proof. */
	void menuOpened()
	{
		invalidate();
	}

	void invalidate()
	{
		hasProof = false;
		proofTree = null;
		proofState = null;
		proofViewport = null;
	}

	boolean hasProof()
	{
		return hasProof;
	}

	Decision lastDecision()
	{
		return lastDecision;
	}

	Verdict lastVerdict()
	{
		return lastVerdict;
	}

	Menu[] proofChain()
	{
		return proofChain.clone();
	}

	/** Decides from public state and the frame's mouse sample only, before the probe runs. */
	Decision decide(Frame frame)
	{
		lastVerdict = Verdict.NONE;
		lastDecision = decideInternal(frame);
		return lastDecision;
	}

	private Decision decideInternal(Frame frame)
	{
		if (!hasProof)
		{
			return probe(ProbeReason.NO_PROOF, null);
		}
		if (frame.root != proofTree.root)
		{
			return invalidating(ProbeReason.ROOT, null);
		}
		if (frame.canvasWidth != proofCanvasWidth || frame.canvasHeight != proofCanvasHeight)
		{
			return invalidating(ProbeReason.CANVAS, null);
		}
		if (frame.scrollable != proofScrollable || frame.scroll != proofScroll)
		{
			return invalidating(ProbeReason.SCROLL, null);
		}
		String shape = shapeMismatch(proofTree, frame.tree);
		if (shape != null)
		{
			return invalidating(ProbeReason.GEOMETRY, shape);
		}
		String structure = proofState.mismatchReason(frame.root);
		if (structure != null)
		{
			return invalidating(ProbeReason.STRUCTURE, structure);
		}
		// Every sample since the proof counts, including frames that end up probing.
		hoverStableSinceProof &= hover(frame.mouse) == proofHover;
		settledSinceProof &= settled(frame.mouse);
		int age = frame.cycle - proofCycle;
		if (age == 0)
		{
			return new Decision(Kind.REUSE_SAME_CYCLE, null, null, proofChain, 0);
		}
		if (age < 0)
		{
			return invalidating(ProbeReason.CYCLE_BACKWARDS, "age=" + age);
		}
		if (!settledSinceProof)
		{
			return probe(ProbeReason.UNSETTLED, "age=" + age);
		}
		if (!hoverStableSinceProof)
		{
			return probe(ProbeReason.HOVER_CHANGED, "age=" + age);
		}
		if (age > MAX_SETTLED_REUSE_CYCLES)
		{
			return probe(ProbeReason.WINDOW_EXPIRED, "age=" + age);
		}
		return new Decision(Kind.REUSE_SETTLED, null, null, proofChain, age);
	}

	/**
	 * Records the authoritative probe's proven chain. A PROBE decision adopts it as the new
	 * proof, as a cache would; a reuse decision is only verified against it.
	 */
	Verdict observeProven(Decision decision, Frame frame, int[] activeChain)
	{
		Menu[] probed = new Menu[activeChain.length];
		int[] nodes = new int[activeChain.length];
		for (int i = 0; i < activeChain.length; i++)
		{
			nodes[i] = activeChain[i] + 1;
			probed[i] = frame.tree.nodes.get(nodes[i]).menu;
		}
		Verdict verdict;
		if (decision == null || decision.kind == Kind.PROBE)
		{
			verdict = Verdict.NONE;
			adopt(frame, activeChain, nodes, probed);
		}
		else if (sameMenus(decision.chain, probed))
		{
			verdict = Verdict.MATCH;
		}
		else
		{
			verdict = Verdict.MISMATCH;
			// Resynchronise so one native change is counted once, not on every later frame.
			adopt(frame, activeChain, nodes, probed);
		}
		lastVerdict = verdict;
		return verdict;
	}

	/** The probe was ambiguous or rejected: a reuse decision cannot be verified. */
	Verdict observeUnproven(Decision decision)
	{
		lastVerdict = decision == null || decision.kind == Kind.PROBE ? Verdict.NONE : Verdict.UNVERIFIED;
		return lastVerdict;
	}

	private void adopt(Frame frame, int[] activeChain, int[] nodes, Menu[] chain)
	{
		hasProof = true;
		proofTree = frame.tree;
		proofState = PreparedMenuState.capture(frame.tree, activeChain);
		proofNodes = nodes;
		proofChain = chain;
		proofViewport = frame.viewport;
		proofScrollable = frame.scrollable;
		proofScroll = frame.scroll;
		proofCanvasWidth = frame.canvasWidth;
		proofCanvasHeight = frame.canvasHeight;
		proofCycle = frame.cycle;
		proofHover = hover(frame.mouse);
		settledSinceProof = settled(frame.mouse);
		hoverStableSinceProof = true;
	}

	private Decision probe(ProbeReason reason, String detail)
	{
		return new Decision(Kind.PROBE, reason, detail, null, 0);
	}

	private Decision invalidating(ProbeReason reason, String detail)
	{
		invalidate();
		return probe(reason, detail);
	}

	static String shapeMismatch(SubmenuTree proof, SubmenuTree current)
	{
		if (proof.nodes.size() != current.nodes.size())
		{
			return "nodes " + proof.nodes.size() + " -> " + current.nodes.size();
		}
		for (int i = 0; i < proof.nodes.size(); i++)
		{
			SubmenuTree.Node before = proof.nodes.get(i);
			SubmenuTree.Node now = current.nodes.get(i);
			if (before.menu != now.menu || before.parentNode != now.parentNode
				|| before.parentEntryIndex != now.parentEntryIndex)
			{
				return "identity node=" + i;
			}
			if (!before.bounds.equals(now.bounds))
			{
				return "bounds node=" + i + " " + before.bounds + " -> " + now.bounds;
			}
		}
		return null;
	}

	/**
	 * Native's hover target for a pointer, walking the proven chain: (node << 16) | (row + 1),
	 * or -1 outside every proven menu's rows.
	 */
	private long hover(Point mouse)
	{
		int node = 0;
		for (int depth = 0; ; depth++)
		{
			int open = depth < proofNodes.length ? proofNodes[depth] : -1;
			if (open >= 0 && mouse != null && contains(proofTree.nodes.get(open).bounds, mouse))
			{
				node = open;
				continue;
			}
			int row = row(node, mouse);
			return row < 0 ? -1 : ((long) node << 16) | (row + 1);
		}
	}

	/** Whether native has no pending submenu switch for this pointer under the proven chain. */
	private boolean settled(Point mouse)
	{
		int node = 0;
		for (int depth = 0; ; depth++)
		{
			int open = depth < proofNodes.length ? proofNodes[depth] : -1;
			if (open >= 0 && mouse != null && contains(proofTree.nodes.get(open).bounds, mouse))
			{
				node = open;
				continue;
			}
			int row = row(node, mouse);
			if (row < 0)
			{
				return true; // Native ignores a pointer outside this menu's rows.
			}
			MenuEntry[] entries = proofTree.nodes.get(node).entries;
			int entryIndex = entries.length - 1 - row;
			Menu target = entries[entryIndex] == null ? null : entries[entryIndex].getSubMenu();
			Menu proven = open >= 0 ? proofTree.nodes.get(open).menu : null;
			return target == proven;
		}
	}

	private int row(int node, Point mouse)
	{
		if (mouse == null)
		{
			return -1;
		}
		SubmenuTree.Node menu = proofTree.nodes.get(node);
		if (node == 0 && proofViewport != null)
		{
			return proofViewport.hoverIndex(mouse);
		}
		Rectangle bounds = menu.bounds;
		if (mouse.getX() < bounds.x || mouse.getX() >= bounds.x + bounds.width
			|| mouse.getY() < bounds.y + MenuRenderer.HEADER_HEIGHT
			|| mouse.getY() >= bounds.y + bounds.height)
		{
			return -1;
		}
		int row = (mouse.getY() - bounds.y - MenuRenderer.HEADER_HEIGHT) / MenuRenderer.ROW_HEIGHT;
		return row < menu.entries.length ? row : -1;
	}

	private static boolean contains(Rectangle bounds, Point mouse)
	{
		return bounds.width > 0 && bounds.height > 0 && bounds.contains(mouse.getX(), mouse.getY());
	}

	private static boolean sameMenus(Menu[] first, Menu[] second)
	{
		if (first.length != second.length)
		{
			return false;
		}
		for (int i = 0; i < first.length; i++)
		{
			if (first[i] != second[i])
			{
				return false;
			}
		}
		return true;
	}

	static String describe(Menu[] chain)
	{
		StringBuilder text = new StringBuilder("[");
		for (Menu menu : chain)
		{
			if (text.length() > 1)
			{
				text.append(", ");
			}
			text.append(Integer.toHexString(System.identityHashCode(menu))).append('@')
				.append(new Rectangle(menu.getMenuX(), menu.getMenuY(), menu.getMenuWidth(), menu.getMenuHeight()));
		}
		return text.append(']').toString();
	}

	@Override
	public String toString()
	{
		return "SubmenuChainShadow{proof=" + hasProof + " chain=" + Arrays.toString(proofNodes) + "}";
	}
}
