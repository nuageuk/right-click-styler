package com.rightclickstyler;

import java.lang.reflect.Proxy;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The reuse rules of {@link SubmenuChainShadow} on their own. The root has 20 rows at
 * (100,100); rows 3, 6 and 9 open five-row children, every other row is plain. Theme and
 * configuration are not inputs to the shadow at all, so no test is needed for them.
 */
public class SubmenuChainShadowTest
{
	private static final int ROWS = 20;
	private static final int CANVAS_WIDTH = 990;
	private static final int CANVAS_HEIGHT = 592;

	private final TestMenu root = new TestMenu(100, 100, 200, 22 + ROWS * MenuRenderer.ROW_HEIGHT);
	private final String[] options = new String[ROWS];
	private final TestMenu[] childAtRow = new TestMenu[ROWS];
	private final String[] childOptions = {"Child action"};
	private final SubmenuChainShadow shadow = new SubmenuChainShadow();
	private int cycle = 500;

	public SubmenuChainShadowTest()
	{
		MenuEntry[] entries = new MenuEntry[ROWS];
		for (int row = 0; row < ROWS; row++)
		{
			int entry = ROWS - 1 - row;
			options[entry] = "Root " + entry;
			if (row == 3 || row == 6 || row == 9)
			{
				TestMenu child = new TestMenu(0, 0, 0, 0);
				MenuEntry[] childEntries = new MenuEntry[5];
				for (int i = 0; i < childEntries.length; i++)
				{
					childEntries[i] = entry(childOptions, 0, null);
				}
				child.entries = childEntries;
				childAtRow[row] = child;
			}
			entries[entry] = entry(options, entry, childAtRow[row]);
		}
		root.entries = entries;
	}

	@Test
	public void firstFrameProbesAndSameCycleReuseIgnoresTheMouse()
	{
		open(3);
		assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.NO_PROOF, decide(onRow(3)));
		prove(onRow(3), 3);
		// Same cycle: native cannot have switched, wherever the live pointer has gone.
		for (Point anywhere : new Point[]{onRow(9), onRow(12), inChild(3), new Point(-1, -1), null})
		{
			assertKind(SubmenuChainShadow.Kind.REUSE_SAME_CYCLE, null, decide(anywhere));
		}
		assertArrayEquals(new Menu[]{childAtRow[3]}, shadow.lastDecision().chain);
	}

	@Test
	public void pendingDelayedOpenIsNeverReusedHoweverLongTheMouseStaysStill()
	{
		open(3);
		Point parked = onRow(6); // row 6 opens another child after native's open delay
		decide(parked);
		prove(parked, 3);
		for (int i = 0; i < 50; i++)
		{
			cycle++;
			assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.UNSETTLED, decide(parked));
		}
	}

	@Test
	public void pendingDelayedCloseOnAPlainRowIsNeverReused()
	{
		open(3);
		Point plain = onRow(4);
		decide(plain);
		prove(plain, 3);
		cycle++;
		assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.UNSETTLED, decide(plain));
	}

	@Test
	public void settledReuseIsBoundedEvenWithAnUnchangedMouse()
	{
		open(3);
		Point inside = inChild(3);
		decide(inside);
		prove(inside, 3);
		for (int age = 1; age <= SubmenuChainShadow.MAX_SETTLED_REUSE_CYCLES; age++)
		{
			cycle++;
			SubmenuChainShadow.Decision decision = decide(inside);
			assertKind(SubmenuChainShadow.Kind.REUSE_SETTLED, null, decision);
			assertEquals(age, decision.age);
			// A verified reuse does not refresh the proof: the age keeps growing.
			assertEquals(SubmenuChainShadow.Verdict.MATCH, shadow.observeProven(decision, frame(inside), chain(3)));
		}
		cycle++;
		assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.WINDOW_EXPIRED, decide(inside));
	}

	@Test
	public void settledHoversCoverParentRowPlainChildRowsAndOutside()
	{
		for (Point settled : new Point[]{onRow(3), inChild(3), new Point(20, 20), null})
		{
			SubmenuChainShadowTest test = new SubmenuChainShadowTest();
			test.open(3);
			test.decide(settled);
			test.prove(settled, 3);
			test.cycle++;
			assertKind(SubmenuChainShadow.Kind.REUSE_SETTLED, null, test.decide(settled));
		}
		SubmenuChainShadowTest rootOnly = new SubmenuChainShadowTest();
		Point plainRow = onRow(12);
		rootOnly.decide(plainRow);
		rootOnly.prove(plainRow);
		rootOnly.cycle++;
		assertKind(SubmenuChainShadow.Kind.REUSE_SETTLED, null, rootOnly.decide(plainRow));
	}

	@Test
	public void anyHoverChangeSinceTheProofEndsSettledReuse()
	{
		open(3);
		decide(onRow(3));
		prove(onRow(3), 3);
		// Both positions are settled, but the pointer passed between them after the proof.
		assertKind(SubmenuChainShadow.Kind.REUSE_SAME_CYCLE, null, decide(inChild(3)));
		cycle++;
		assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.HOVER_CHANGED, decide(onRow(3)));
		// An unsettled sample in between also ends it, even after returning.
		SubmenuChainShadowTest other = new SubmenuChainShadowTest();
		other.open(3);
		other.decide(onRow(3));
		other.prove(onRow(3), 3);
		other.decide(onRow(6));
		other.cycle++;
		assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.UNSETTLED, other.decide(onRow(3)));
	}

	@Test
	public void menuOpeningEndsTheProofEvenWithIdenticalRootAndGeometry()
	{
		open(3);
		decide(onRow(3));
		prove(onRow(3), 3);
		shadow.menuOpened();
		assertFalse(shadow.hasProof());
		assertKind(SubmenuChainShadow.Kind.PROBE, SubmenuChainShadow.ProbeReason.NO_PROOF, decide(onRow(3)));
	}

	@Test
	public void structuralChangesInvalidateTheProof()
	{
		assertInvalidates(SubmenuChainShadow.ProbeReason.GEOMETRY, t -> t.childAtRow[9].setBounds(300, 200, 150, 97));
		assertInvalidates(SubmenuChainShadow.ProbeReason.GEOMETRY, t -> t.open(6));
		assertInvalidates(SubmenuChainShadow.ProbeReason.STRUCTURE, t -> t.options[ROWS - 1 - 12] = "Changed");
		assertInvalidates(SubmenuChainShadow.ProbeReason.STRUCTURE, t -> t.childOptions[0] = "Changed");
		assertInvalidates(SubmenuChainShadow.ProbeReason.ROOT, t -> t.rootOverride = new TestMenu(100, 100, 200, 322));
		assertInvalidates(SubmenuChainShadow.ProbeReason.SCROLL, t -> t.scroll = 1);
		assertInvalidates(SubmenuChainShadow.ProbeReason.CANVAS, t -> t.canvasHeight = 600);
		assertInvalidates(SubmenuChainShadow.ProbeReason.CYCLE_BACKWARDS, t -> t.cycle -= 1);
	}

	@Test
	public void unprovenProbesAreUnverifiedAndMismatchesResynchronise()
	{
		open(3);
		decide(onRow(3));
		prove(onRow(3), 3);
		SubmenuChainShadow.Decision reuse = decide(onRow(3));
		assertEquals(SubmenuChainShadow.Verdict.UNVERIFIED, shadow.observeUnproven(reuse));
		assertTrue("an unproven probe neither confirms nor clears the proof", shadow.hasProof());
		assertArrayEquals(new Menu[]{childAtRow[3]}, shadow.proofChain());
		reuse = decide(onRow(3));
		assertEquals(SubmenuChainShadow.Verdict.MISMATCH, shadow.observeProven(reuse, frame(onRow(3)), new int[0]));
		assertArrayEquals("the probe's chain replaces the proof", new Menu[0], shadow.proofChain());
		shadow.menuOpened();
		SubmenuChainShadow.Decision probe = decide(onRow(3));
		assertEquals(SubmenuChainShadow.Kind.PROBE, probe.kind);
		assertEquals(SubmenuChainShadow.Verdict.NONE, shadow.observeUnproven(probe));
	}

	private interface Change
	{
		void apply(SubmenuChainShadowTest test);
	}

	private Menu rootOverride;
	private int scroll;
	private int canvasHeight = CANVAS_HEIGHT;

	private static void assertInvalidates(SubmenuChainShadow.ProbeReason reason, Change change)
	{
		SubmenuChainShadowTest test = new SubmenuChainShadowTest();
		test.open(3);
		test.childAtRow[9].setBounds(300, 235, 180, 97); // a retained, closed child
		test.decide(inChild(3));
		test.prove(inChild(3), 3);
		change.apply(test);
		assertKind(SubmenuChainShadow.Kind.PROBE, reason, test.decide(inChild(3)));
		assertFalse(reason + " must discard the proof", test.shadow.hasProof());
	}

	private static void assertKind(SubmenuChainShadow.Kind kind, SubmenuChainShadow.ProbeReason reason,
		SubmenuChainShadow.Decision decision)
	{
		assertEquals(decision.toString(), kind, decision.kind);
		assertEquals(decision.toString(), reason, decision.reason);
	}

	private SubmenuChainShadow.Decision decide(Point mouse)
	{
		return shadow.decide(frame(mouse));
	}

	/** Adopts the probe result as a PROBE frame would. */
	private void prove(Point mouse, int... openRows)
	{
		SubmenuChainShadow.Decision decision = shadow.lastDecision();
		assertEquals(SubmenuChainShadow.Kind.PROBE, decision.kind);
		assertEquals(SubmenuChainShadow.Verdict.NONE, shadow.observeProven(decision, frame(mouse), chain(openRows)));
		assertTrue(shadow.hasProof());
	}

	private int[] chain(int... openRows)
	{
		SubmenuTree tree = SubmenuTree.capture(currentRoot());
		int[] chain = new int[openRows.length];
		for (int i = 0; i < openRows.length; i++)
		{
			for (int node = 1; node < tree.nodes.size(); node++)
			{
				if (tree.nodes.get(node).menu == childAtRow[openRows[i]])
				{
					chain[i] = node - 1;
				}
			}
		}
		return chain;
	}

	private SubmenuChainShadow.Frame frame(Point mouse)
	{
		Menu current = currentRoot();
		SubmenuTree tree = SubmenuTree.capture(current);
		ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(tree.nodes.get(0).bounds,
			tree.nodes.get(0).entries.length, CANVAS_WIDTH, CANVAS_HEIGHT, false, 0);
		return new SubmenuChainShadow.Frame(current, tree, viewport, scroll != 0, scroll,
			CANVAS_WIDTH, canvasHeight, cycle, mouse);
	}

	private Menu currentRoot()
	{
		if (rootOverride != null)
		{
			((TestMenu) rootOverride).entries = root.entries;
			return rootOverride;
		}
		return root;
	}

	/** Natively opens the child of a row, positioned beside its parent row. */
	private void open(int row)
	{
		childAtRow[row].setBounds(300, rowTop(row) - MenuRenderer.HEADER_HEIGHT, 160, 22 + 5 * MenuRenderer.ROW_HEIGHT);
	}

	private static int rowTop(int row)
	{
		return 100 + MenuRenderer.HEADER_HEIGHT + row * MenuRenderer.ROW_HEIGHT;
	}

	private static Point onRow(int row)
	{
		return new Point(150, rowTop(row) + 7);
	}

	private static Point inChild(int row)
	{
		return new Point(360, rowTop(row) + 30);
	}

	private static final class TestMenu implements Menu
	{
		private int x;
		private int y;
		private int width;
		private int height;
		private MenuEntry[] entries = new MenuEntry[0];

		private TestMenu(int x, int y, int width, int height)
		{
			setBounds(x, y, width, height);
		}

		private void setBounds(int x, int y, int width, int height)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		@Override
		public MenuEntry createMenuEntry(int idx)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public MenuEntry[] getMenuEntries()
		{
			return entries.clone();
		}

		@Override
		public void setMenuEntries(MenuEntry[] entries)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void removeMenuEntry(MenuEntry entry)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public int getMenuX()
		{
			return x;
		}

		@Override
		public int getMenuY()
		{
			return y;
		}

		@Override
		public int getMenuWidth()
		{
			return width;
		}

		@Override
		public int getMenuHeight()
		{
			return height;
		}
	}

	private static MenuEntry entry(String[] options, int index, Menu subMenu)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getOption": return options[index];
					case "getTarget": return "";
					case "getSubMenu": return subMenu;
					case "getType": return MenuAction.RUNELITE;
					case "getItemId": return -1;
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					case "toString": return options[index];
					default:
						break;
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? (Object) false : type == int.class ? (Object) 0 : null;
			});
	}
}
