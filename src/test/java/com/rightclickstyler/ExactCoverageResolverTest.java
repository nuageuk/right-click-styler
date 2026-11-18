package com.rightclickstyler;

import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Deterministic fixtures with a known native active chain. Each paints the root and that chain
 * the way the native menu draw does, runs the real probe, and checks both the probe's own
 * resolver and the shadow exact-coverage reading.
 */
public class ExactCoverageResolverTest
{
	static final int WIDTH = 990;
	static final int HEIGHT = 592;
	private static final int NATIVE_COLOR = 0xFF5D5447;

	@Before
	public void requireExactShadow()
	{
		assumeTrue("exact-coverage shadow disabled", SubmenuPrototypeProbe.exactCoverageShadowEnabled());
	}

	@Test
	public void loggedNearTieSiblingsResolveToTheDrawnWidth()
	{
		// Frame 587 of submenu-step1-analysis.log: three retained siblings at one position
		// whose widths differ by 1-13px. The probe's 3px footprint tolerance matches three.
		Fixture f = loggedFrame587();
		SubmenuPrototypeProbe.Result result = f.probe(Paint.OPAQUE, f.child(2));
		assertEquals(SubmenuPrototypeProbe.State.AMBIGUOUS, result.state);
		assertResolved(result, 2);
	}

	@Test
	public void eachNearTieSiblingResolvesToItselfWhenDrawn()
	{
		Fixture f = loggedFrame587();
		for (int drawn : new int[]{0, 2, 3, 21, 7})
		{
			assertResolved(f.probe(Paint.OPAQUE, f.child(drawn)), drawn);
		}
	}

	@Test
	public void retainedRectanglesWithoutAnOpenChildResolveToRootOnly()
	{
		Fixture f = loggedFrame587();
		SubmenuPrototypeProbe.Result result = f.probe(Paint.OPAQUE);
		assertEquals(SubmenuPrototypeProbe.State.NO_CHILD, result.state);
		assertResolved(result);
	}

	@Test
	public void identicalRectanglesStayAmbiguous()
	{
		Fixture f = new Fixture(100, 4);
		f.child(0).setBounds(328, 300, 220, 97);
		f.child(1).setBounds(328, 300, 220, 97);
		SubmenuPrototypeProbe.Result result = f.probe(Paint.OPAQUE, f.child(1));
		assertEquals(SubmenuPrototypeProbe.State.AMBIGUOUS, result.state);
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS, result.exact.state);
		assertEquals(2, result.exact.explainingChains);
	}

	@Test
	public void differenceClippedOffTheCanvasStaysAmbiguous()
	{
		Fixture f = new Fixture(100, 4);
		f.child(0).setBounds(900, 300, 120, 97); // visible to x=989 either way
		f.child(1).setBounds(900, 300, 110, 97);
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS, f.probe(Paint.OPAQUE, f.child(1)).exact.state);
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS, f.probe(Paint.OPAQUE, f.child(0)).exact.state);
	}

	@Test
	public void clippedChildrenResolveWhenTheDifferenceIsVisible()
	{
		Fixture f = new Fixture(100, 4);
		f.child(0).setBounds(850, 540, 130, 97); // clipped at the bottom edge
		f.child(1).setBounds(850, 540, 120, 97);
		f.child(2).setBounds(850, 500, 125, 97);
		assertResolved(f.probe(Paint.OPAQUE, f.child(1)), 1);
		assertResolved(f.probe(Paint.OPAQUE, f.child(0)), 0);
		assertResolved(f.probe(Paint.OPAQUE, f.child(2)), 2);
	}

	@Test
	public void nestedChainWithANearTieGrandchildResolvesTheWholeChain()
	{
		NestedFixture f = new NestedFixture();
		f.parent.setBounds(328, 200, 220, 97);
		f.drawnGrandchild.setBounds(548, 215, 180, 97);
		f.staleGrandchild.setBounds(548, 215, 176, 97);
		SubmenuPrototypeProbe.Result result = f.probe(Paint.OPAQUE, f.parent, f.drawnGrandchild);
		assertResolved(result, f.index(f.parent), f.index(f.drawnGrandchild));
		assertResolved(f.probe(Paint.OPAQUE, f.parent, f.staleGrandchild), f.index(f.parent), f.index(f.staleGrandchild));
		assertResolved(f.probe(Paint.OPAQUE, f.parent), f.index(f.parent));
	}

	@Test
	public void grandchildContainedInItsParentIsIndistinguishable()
	{
		NestedFixture f = new NestedFixture();
		f.parent.setBounds(328, 200, 220, 97);
		f.drawnGrandchild.setBounds(340, 210, 150, 60); // entirely under its parent
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS, f.probe(Paint.OPAQUE, f.parent).exact.state);
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS,
			f.probe(Paint.OPAQUE, f.parent, f.drawnGrandchild).exact.state);
	}

	@Test
	public void translucentNativeMenusResolveLikeOpaqueOnes()
	{
		Fixture f = loggedFrame587();
		for (int drawn : new int[]{2, 21})
		{
			assertResolved(f.probe(Paint.TRANSLUCENT, f.child(drawn)), drawn);
		}
	}

	@Test
	public void unpaintedPixelsInsideTheDrawnChildAreNeverGuessed()
	{
		Fixture f = loggedFrame587();
		SubmenuPrototypeProbe.Result result = f.probe(Paint.WITH_HOLE, f.child(2));
		assertEquals("evidence against full coverage", ExactCoverageResolver.State.UNEXPLAINED, result.exact.state);
	}

	@Test
	public void drawingOutsideTheChainIsNeverGuessed()
	{
		Fixture f = loggedFrame587();
		SubmenuPrototypeProbe.Result result = f.probe(Paint.WITH_HALO, f.child(2));
		assertNotEquals(ExactCoverageResolver.State.RESOLVED, result.exact.state);
	}

	@Test
	public void candidateWithoutEvidenceOutsideTheRootIsNeverGuessed()
	{
		Fixture f = new Fixture(100, 20);
		f.child(0).setBounds(120, 100, 150, 97); // entirely under the root
		SubmenuPrototypeProbe.Result result = f.probe(Paint.OPAQUE);
		assertEquals(ExactCoverageResolver.State.UNEVIDENCED, result.exact.state);
	}

	@Test
	public void rowsAboveTheScrollOffsetAreNotCandidates()
	{
		// Native skips rows above the scroll offset before drawing their children, so a
		// retained child of such a row cannot be on screen. Both resolvers depend on this.
		Fixture skipped = new Fixture(100, 6, 2);
		skipped.childAtRow(0).setBounds(328, 300, 220, 97); // above the offset
		skipped.childAtRow(4).setBounds(328, 300, 220, 97);
		assertResolved(skipped.probe(Paint.OPAQUE, skipped.childAtRow(4)), skipped.index(skipped.childAtRow(4)));
		Fixture visible = new Fixture(100, 6, 0);
		visible.childAtRow(0).setBounds(328, 300, 220, 97);
		visible.childAtRow(4).setBounds(328, 300, 220, 97);
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS,
			visible.probe(Paint.OPAQUE, visible.childAtRow(4)).exact.state);
	}

	@Test
	public void exclusiveEvidenceAgreesWithTheExistingResolver()
	{
		Fixture f = new Fixture(100, 4);
		f.child(0).setBounds(328, 100, 220, 97);
		f.child(1).setBounds(328, 300, 220, 97);
		SubmenuPrototypeProbe.Result result = f.probe(Paint.OPAQUE, f.child(1));
		assertEquals(SubmenuPrototypeProbe.State.ACTIVE, result.state);
		assertArrayEquals(new int[]{1}, result.activeChain);
		assertResolved(result, 1);
	}

	@Test
	public void resolverNeverModifiesPixels()
	{
		Fixture f = loggedFrame587();
		int[] pixels = new int[WIDTH * HEIGHT];
		for (int i = 0; i < pixels.length; i++)
		{
			pixels[i] = i * 31;
		}
		int[] original = pixels.clone();
		SubmenuPrototypeProbe.Result result = SubmenuPrototypeProbe.probePixels(pixels, WIDTH, HEIGHT,
			SubmenuTree.capture(f.root), () -> Paint.OPAQUE.paint(pixels, f.root, f.child(2)));
		assertResolved(result, 2);
		assertArrayEquals("probe and resolver leave the framebuffer as it was", original, pixels);
	}

	static void assertResolved(SubmenuPrototypeProbe.Result result, int... chain)
	{
		assertEquals(result.exact.toString(), ExactCoverageResolver.State.RESOLVED, result.exact.state);
		assertArrayEquals(result.exact.toString(), chain, result.exact.chain);
	}

	/** Frame 587's retained siblings, all children of a 40-row root at x=421. */
	static Fixture loggedFrame587()
	{
		Fixture f = new Fixture(421, 40);
		f.child(0).setBounds(649, 495, 235, 97);
		f.child(2).setBounds(649, 495, 232, 97);
		f.child(3).setBounds(649, 495, 222, 97);
		f.child(4).setBounds(649, 480, 241, 97);
		f.child(6).setBounds(649, 450, 185, 97);
		f.child(7).setBounds(649, 450, 234, 97);
		f.child(21).setBounds(649, 495, 231, 97);
		return f;
	}

	enum Paint
	{
		OPAQUE, TRANSLUCENT, WITH_HOLE, WITH_HALO;

		void paint(int[] pixels, Menu root, Menu... chain)
		{
			fill(pixels, bounds(root));
			for (Menu menu : chain)
			{
				Rectangle bounds = bounds(menu);
				fill(pixels, bounds);
				if (this == WITH_HOLE)
				{
					restoreHole(pixels, bounds);
				}
				if (this == WITH_HALO)
				{
					// A 2px band above the menu. (A band that exactly reproduced a wider retained
					// sibling would be pixel-identical to that sibling being open: undetectable.)
					fill(pixels, new Rectangle(bounds.x, bounds.y - 2, bounds.width, 2));
				}
			}
		}

		private void fill(int[] pixels, Rectangle rect)
		{
			Rectangle visible = rect.intersection(new Rectangle(0, 0, WIDTH, HEIGHT));
			for (int y = visible.y; y < visible.y + visible.height; y++)
			{
				for (int x = visible.x; x < visible.x + visible.width; x++)
				{
					int i = y * WIDTH + x;
					pixels[i] = this == TRANSLUCENT ? blend(pixels[i], NATIVE_COLOR, 100) : NATIVE_COLOR;
				}
			}
		}

		/** A 4x4 patch the native draw leaves alone, 40px into the menu. */
		private static void restoreHole(int[] pixels, Rectangle bounds)
		{
			for (int y = bounds.y + 20; y < bounds.y + 24; y++)
			{
				for (int x = bounds.x + 40; x < bounds.x + 44; x++)
				{
					pixels[y * WIDTH + x] = 0x13579BDF; // what the probe marked there
				}
			}
		}

		private static int blend(int under, int over, int alpha)
		{
			int r = (((over >> 16) & 0xFF) * alpha + ((under >> 16) & 0xFF) * (255 - alpha)) / 255;
			int g = (((over >> 8) & 0xFF) * alpha + ((under >> 8) & 0xFF) * (255 - alpha)) / 255;
			int b = ((over & 0xFF) * alpha + (under & 0xFF) * (255 - alpha)) / 255;
			return 0xFF000000 | r << 16 | g << 8 | b;
		}
	}

	static Rectangle bounds(Menu menu)
	{
		return new Rectangle(menu.getMenuX(), menu.getMenuY(), menu.getMenuWidth(), menu.getMenuHeight());
	}

	/** A root whose rows all open a (initially dormant) child, in native entry order. */
	static class Fixture
	{
		final TestMenu root;
		final List<TestMenu> children = new ArrayList<>();
		private final int skippedRows;

		Fixture(int rootX, int rows)
		{
			this(rootX, rows, 0);
		}

		Fixture(int rootX, int rows, int skippedRows)
		{
			this.skippedRows = skippedRows;
			root = new TestMenu(rootX, 0, 228, 22 + rows * MenuRenderer.ROW_HEIGHT);
			MenuEntry[] entries = new MenuEntry[rows];
			for (int entry = 0; entry < rows; entry++)
			{
				TestMenu child = new TestMenu(0, 0, 0, 0);
				child.entries = plainEntries(5);
				children.add(child);
				entries[entry] = entry("Root " + entry, child);
			}
			root.entries = entries;
		}

		/** The child of native entry {@code entry}, which is also its candidate index. */
		TestMenu child(int entry)
		{
			return children.get(entry);
		}

		TestMenu childAtRow(int row)
		{
			return children.get(children.size() - 1 - row);
		}

		int index(Menu menu)
		{
			SubmenuTree tree = SubmenuTree.capture(root, null, skippedRows);
			for (int node = 1; node < tree.nodes.size(); node++)
			{
				if (tree.nodes.get(node).menu == menu)
				{
					return node - 1;
				}
			}
			throw new AssertionError("not a candidate");
		}

		SubmenuPrototypeProbe.Result probe(Paint paint, Menu... chain)
		{
			int[] pixels = new int[WIDTH * HEIGHT];
			SubmenuTree tree = SubmenuTree.capture(root, null, skippedRows);
			SubmenuPrototypeProbe.Result result = SubmenuPrototypeProbe.probePixels(pixels, WIDTH, HEIGHT,
				tree, () -> paint.paint(pixels, root, chain));
			assertTrue("framebuffer restored", Arrays.equals(new int[pixels.length], pixels));
			return result;
		}
	}

	/** Root row 0 opens {@link #parent}, whose rows open two grandchildren. */
	static final class NestedFixture extends Fixture
	{
		final TestMenu parent;
		final TestMenu drawnGrandchild = new TestMenu(0, 0, 0, 0);
		final TestMenu staleGrandchild = new TestMenu(0, 0, 0, 0);

		NestedFixture()
		{
			super(100, 4);
			parent = childAtRow(0);
			drawnGrandchild.entries = plainEntries(5);
			staleGrandchild.entries = plainEntries(5);
			MenuEntry[] parentEntries = plainEntries(5);
			parentEntries[4] = entry("Drawn", drawnGrandchild);
			parentEntries[3] = entry("Stale", staleGrandchild);
			parent.entries = parentEntries;
		}
	}

	static MenuEntry[] plainEntries(int count)
	{
		MenuEntry[] entries = new MenuEntry[count];
		for (int i = 0; i < count; i++)
		{
			entries[i] = entry("Action " + i, null);
		}
		return entries;
	}

	static final class TestMenu implements Menu
	{
		private int x;
		private int y;
		private int width;
		private int height;
		MenuEntry[] entries = new MenuEntry[0];

		TestMenu(int x, int y, int width, int height)
		{
			setBounds(x, y, width, height);
		}

		void setBounds(int x, int y, int width, int height)
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

	static MenuEntry entry(String option, Menu subMenu)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getOption": return option;
					case "getTarget": return "";
					case "getSubMenu": return subMenu;
					case "getType": return MenuAction.RUNELITE;
					case "getItemId": return -1;
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					case "toString": return option;
					default:
						break;
				}
				Class<?> type = method.getReturnType();
				return type == boolean.class ? (Object) false : type == int.class ? (Object) 0 : null;
			});
	}
}
