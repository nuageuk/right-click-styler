package com.rightclickstyler;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.IntPredicate;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Rasterizer;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Lifecycle tests against a model of RuneLite's native submenu state.
 *
 * <p>The model follows the injected client: the open submenu changes only in the client's
 * per-cycle input step, from that cycle's mouse snapshot and after an open delay; a closed
 * child keeps its old bounds; and the native menu draw reads that state without changing it.
 * {@code getMouseCanvasPosition} instead follows the AWT thread live, so here it can move
 * between any two client calls of a single frame, including during the native probe draw.
 *
 * <p>Every test runs twice through the real {@code BeforeMenuRender} handler and overlay: with
 * the image cache off and on.
 */
@RunWith(Parameterized.class)
public class NativeSubmenuCycleTest
{
	/** The cached mode re-renders every reused image and fails on any pixel difference. */
	@Parameters(name = "imageCache={0}")
	public static Collection<Object[]> modes()
	{
		return Arrays.asList(new Object[]{false}, new Object[]{true});
	}

	private final boolean imageCache;

	public NativeSubmenuCycleTest(boolean imageCache)
	{
		this.imageCache = imageCache;
	}

	private Harness harness(NativeModel model) throws Exception
	{
		return new Harness(model, imageCache ? 1 : -1);
	}

	private static final int CANVAS_WIDTH = 990;
	private static final int CANVAS_HEIGHT = 592;
	private static final int OPEN_DELAY_CYCLES = 2;
	private static final int CHILD_ROWS = 5;
	private static final int GRAY = 0xFF808080;
	/** The client's menu fill colour. */
	private static final int NATIVE_FILL = 0xFF5D5447;

	@Test
	public void scrollablePlayerMenuWithoutChildrenUsesSubmenuPath() throws Exception
	{
		NativeModel model = new NativeModel(434, 0, 212, 76, true, 0, entry -> false);
		model.live = new Point(500, 80);
		Harness harness = harness(model);
		Frame first = harness.frame();
		assertThemedNativeState("scrollable root at first row", first, model);
		model.scroll = 20;
		Frame scrolled = harness.frame();
		assertThemedNativeState("scrollable root after scrolling", scrolled, model);
	}

	@Test
	public void condensedPlayerMenuWithSiblingChildrenUsesSubmenuPath() throws Exception
	{
		NativeModel model = new NativeModel(434, 130, 212, 4, false, 0, entry -> entry > 0);
		for (int i = 1; i < 4; i++)
		{
			model.root.entries[i] = entry("", "<col=ffffff>Player " + i,
				MenuAction.RUNELITE_PLAYER, model.childByEntry[i]);
		}
		model.live = new Point(500, 180);
		Harness harness = harness(model);
		assertThemedNativeState("condensed players, no open child", harness.frame(), model);
		model.openLogged(1, 646, 145, 210);
		assertThemedNativeState("condensed players, one of three children open", harness.frame(), model);
	}

	/** The first FALLBACK_TRANSIENT record of each distinct kind in submenu-analysis.log. */
	@Test
	public void loggedFalseFallbacksThemeTheCycleNativeState() throws Exception
	{
		// frame=35 "mouse path outside -> node=0 row=1" probeMouse=(299,183) validationMouse=(300,210)
		assertLoggedFrame("frame 35", () ->
		{
			NativeModel model = new NativeModel(190, 176, 218, 3, false, 0, entry -> entry == 0);
			return model;
		}, new Point(299, 183), new Point(300, 210));

		// frame=196 "mouse hover node=0 row=17 -> node=0 row=18" renderedMouse=(549,287)
		// validationMouse=(549,290). Native still had row 15's child open while the pointer
		// was two rows lower: the open delay, not a stale probe.
		assertLoggedFrame("frame 196", () ->
		{
			NativeModel model = longLoggedRoot(0);
			model.retain(12, 646, 180, 196);
			model.openLogged(15, 646, 225, 235);
			return model;
		}, new Point(549, 287), new Point(549, 290));

		// frame=223 "mouse hover node=0 row=7 selected=true -> node=0 row=8 selected=false"
		// renderedMouse=(538,136) validationMouse=(538,139), nine retained child rectangles.
		assertLoggedFrame("frame 223", () ->
		{
			NativeModel model = longLoggedRoot(0);
			model.retain(30, 646, 450, 198);
			model.retain(29, 646, 435, 222);
			model.retain(26, 646, 390, 201);
			model.retain(23, 646, 345, 224);
			model.retain(19, 646, 285, 227);
			model.retain(17, 646, 255, 216);
			model.retain(15, 646, 225, 235);
			model.retain(12, 646, 180, 196);
			model.openLogged(7, 646, 105, 232);
			return model;
		}, new Point(538, 136), new Point(538, 139));

		// frame=562 "mouse hover node=0 row=74 -> node=0 row=73" scroll=38 renderedMouse=(519,559)
		// validationMouse=(519,558), no open child and eight retained rectangles.
		assertLoggedFrame("frame 562", () ->
		{
			NativeModel model = longLoggedRoot(38);
			model.retain(73, 646, 495, 189);
			model.retain(71, 646, 495, 227);
			model.retain(64, 646, 405, 213);
			model.retain(63, 646, 375, 222);
			model.retain(62, 646, 360, 249);
			model.retain(61, 646, 345, 185);
			model.retain(57, 646, 405, 235);
			model.retain(49, 646, 390, 242);
			return model;
		}, new Point(519, 559), new Point(519, 558));

		// frame=720 "mouse path node=0 row=0 -> outside" probeMouse=(475,227) validationMouse=(477,218)
		assertLoggedFrame("frame 720", () ->
		{
			NativeModel model = new NativeModel(381, 201, 241, 10, false, 0,
				entry -> entry >= 2 && entry <= 5);
			model.retain(6, 622, 291, 207);
			model.retain(5, 622, 276, 229);
			model.retain(4, 622, 261, 245);
			return model;
		}, new Point(475, 227), new Point(477, 218));
	}

	@Test
	public void liveMouseRacingEveryClientCallNeverFallsBackOrBlanks() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		List<Point> path = racingPath();
		int[] step = {0};
		model.live = path.get(0);
		model.beforeEachClientCall = () ->
		{
			if (step[0] < path.size() - 1)
			{
				model.live = path.get(++step[0]);
			}
		};
		Harness harness = harness(model);
		int frames = 0;
		while (step[0] < path.size() - 1)
		{
			if (frames % 2 == 0)
			{
				model.cycle(); // client cycles run less often than frames are drawn
			}
			Frame frame = harness.frame();
			assertThemedNativeState("frame " + frames + " mouse=" + model.live, frame, model);
			frames++;
		}
		// The pointer advances 1-4px on every client call (roughly 30 calls per frame), far
		// faster than the 5px median movement the trace logged during one preparation.
		assertTrue("scenario must exercise many frames: " + frames, frames > 60);
		assertTrue("native state must switch children: " + model.openChanges, model.openChanges >= 4);
		assertTrue("closed children must keep stale rectangles", model.retainedCount() >= 3);
		assertEquals(0L, field(harness.overlay, "submenuChangedDrops"));
		assertEquals(0L, field(harness.overlay, "submenuClosedDrops"));
	}

	private static List<Point> racingPath()
	{
		List<Point> path = new ArrayList<>();
		line(path, new Point(549, 30), new Point(549, 330), 1);   // sweep down 20 rows
		dwell(path, new Point(549, 330), 400);                    // row 20 child opens
		line(path, new Point(549, 330), new Point(700, 330), 2);  // into that child
		line(path, new Point(700, 330), new Point(700, 380), 1);  // down inside it
		line(path, new Point(700, 380), new Point(549, 136), 3);  // fast back to row 7
		dwell(path, new Point(549, 136), 400);                    // row 7 child opens
		line(path, new Point(549, 136), new Point(549, 480), 4);  // fast sweep to row 30
		dwell(path, new Point(549, 480), 400);                    // row 30 child opens
		line(path, new Point(549, 480), new Point(300, 480), 3);  // leave the menu
		line(path, new Point(300, 480), new Point(549, 250), 3);  // return to row 15
		dwell(path, new Point(549, 250), 400);
		return path;
	}

	@Test
	public void delayedOpenUnderAParkedMouseIsThemedEveryCycle() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 290); // row 18: native opens its child after the delay
		Harness harness = harness(model);
		NativeMenu rowEighteen = model.childAtRow(18);
		for (int i = 0; i < 12; i++)
		{
			model.cycle();
			assertThemedNativeState("cycle " + i, harness.frame(), model);
		}
		assertTrue("native opened row 18 while the mouse stayed still", model.open == rowEighteen);
	}

	@Test
	public void nativeChangeWithinOneCycleIsThemedFromTheProbe() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("before", harness.frame(), model);
		model.open = model.childAtRow(12); // no cycle, no geometry change
		assertThemedNativeState("rendering follows this frame's probe", harness.frame(), model);
	}

	@Test
	public void swapSubmenuTransitionsAndEquivalentEntryRebuildsStayThemed() throws Exception
	{
		for (boolean opensLeft : new boolean[]{false, true})
		{
			int rootX = opensLeft ? 750 : 434;
			NativeModel model = new NativeModel(rootX, 130, 212, 4, false, 0,
				entry -> entry == 1 || entry == 2);
			model.rootEntryOptions[1] = "Swap shift-click";
			model.rootEntryOptions[2] = "Swap left-click";
			int childX = opensLeft ? 520 : 646;
			model.retain(1, childX, 160, 203);
			model.openLogged(2, childX, 175, 220);
			model.live = new Point(rootX + 40, 180);
			Harness harness = harness(model);
			assertThemedNativeState("shift-click opensLeft=" + opensLeft, harness.frame(), model);
			int entryIndex = model.rows - 1 - 2;
			model.root.entries[entryIndex] = entry(model.rootEntryOptions, entryIndex,
				model.childByEntry[entryIndex]);
			assertThemedNativeState("equivalent entry rebuilt opensLeft=" + opensLeft,
				harness.frame(), model);
			model.open = model.childAtRow(1);
			assertThemedNativeState("left-click switch opensLeft=" + opensLeft,
				harness.frame(), model);
		}
	}

	@Test
	public void switchToAnIndistinguishableChildStaysNative() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.rootEntryOptions[model.rows - 1 - 20] = "Swap shift-click";
		model.rootEntryOptions[model.rows - 1 - 21] = "Swap left-click";
		model.retain(20, 646, 400, 220);
		model.retain(21, 646, 400, 220); // identical rectangles: indistinguishable by pixels
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("before", harness.frame(), model);
		model.open = model.childAtRow(20);
		assertEquals("the previous frame's chain is never rendered", Shown.NATIVE, harness.frame().shown);
	}

	@Test
	public void menuOpeningConsumedEventsScrollAndStructureChangesStayThemed() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("first", harness.frame(), model);
		harness.plugin.onMenuOpened(null); // RuneLite reuses the same root object
		assertThemedNativeState("after the menu reopened", harness.frame(), model);
		model.scroll = 1;
		assertThemedNativeState("after scrolling", harness.frame(), model);
		model.rootEntryOptions[40] = "Changed";
		assertThemedNativeState("after an entry changed", harness.frame(), model);
	}

	// ---- Exact coverage resolution ----

	/** Logged frame 587's near-tie widths at one position, with the middle width open. */
	private static NativeModel nearTieModel()
	{
		return nearTieModel(300); // overlaps the open child's parent row 21 (rowTop 337)
	}

	private static NativeModel nearTieModel(int y)
	{
		NativeModel tie = longLoggedRoot(0);
		tie.retain(20, 646, y, 235);
		tie.retain(22, 646, y, 231);
		tie.openLogged(21, 646, y, 232);
		tie.live = new Point(549, 340);
		return tie;
	}

	@Test
	public void nearTieSiblingsAreThemedByExactCoverage() throws Exception
	{
		Harness harness = harness(nearTieModel());
		Frame frame = harness.frame();
		assertEquals(MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED, comparison(harness));
		assertThemedNativeState("promoted near tie", frame, harness.model);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_CHILD));
		assertEquals(1L, field(harness.overlay, "submenuBlits"));

		// Retained 63px below its parent row: the existing detached-child check applies, and the
		// exact reading is the proof that native drew the child at that position.
		Harness detached = harness(nearTieModel(400));
		Frame detachedFrame = detached.frame();
		assertThemedNativeState("detached near tie", detachedFrame, detached.model);
		assertEquals(1L, outcome(detached, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_DETACHED_CHILD));
	}

	@Test
	public void identicalBoundsNeverSelectEitherChild() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.retain(20, 646, 400, 220);
		model.openLogged(21, 646, 400, 220);
		model.live = new Point(549, 340);
		Harness harness = harness(model);
		Frame frame = harness.frame();
		assertEquals(Shown.NATIVE, frame.shown);
		assertEquals(MenuOverlay.ExactComparison.BOTH_UNRESOLVED, comparison(harness));
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
	}

	/** The live Swap trace: two distinct children retained the same 131x127 rectangle. */
	@Test
	public void liveSwapBoundsStayNativeAcrossSamePointerSwitchAndEntryRebuild() throws Exception
	{
		for (boolean opensLeft : new boolean[]{true, false})
		{
			int rootX = opensLeft ? 683 : 2;
			int childX = opensLeft ? 552 : rootX + 253;
			NativeModel model = new NativeModel(rootX, 420, 253, 10, false, 0,
				entry -> entry == 6 || entry == 7);
			model.rootEntryOptions[model.rows - 1 - 2] = "Swap shift-click";
			model.rootEntryOptions[model.rows - 1 - 3] = "Swap left-click";
			for (int row : new int[]{2, 3})
			{
				NativeMenu child = model.childAtRow(row);
				MenuEntry[] actions = new MenuEntry[7];
				for (int i = 0; i < actions.length; i++)
				{
					actions[i] = entry(new String[]{"Action " + row + "." + i}, 0, null);
				}
				child.entries = actions;
				child.setBounds(childX, 465, 131, 127);
			}
			model.open = model.childAtRow(3);
			model.live = new Point(rootX + 20, 490);
			Harness harness = harness(model);
			assertEquals("left child opensLeft=" + opensLeft, Shown.NATIVE, harness.frame().shown);
			model.open = model.childAtRow(2); // native changes child without moving the pointer
			assertEquals("shift child opensLeft=" + opensLeft, Shown.NATIVE, harness.frame().shown);
			int shiftEntry = model.rows - 1 - 2;
			model.root.entries[shiftEntry] = entry(model.rootEntryOptions, shiftEntry, model.childAtRow(2));
			assertEquals("rebuilt parent opensLeft=" + opensLeft, Shown.NATIVE, harness.frame().shown);
			assertEquals("three genuinely ambiguous frames opensLeft=" + opensLeft, 3L,
				outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
		}
	}

	// ---- Visually equivalent alternatives ----

	/**
	 * Identical Swap submenus (7 rows, 127px): Menu Entry Swapper's case when left-click and
	 * shift-click currently use the same op, so both leave out the same one. Usually they differ;
	 * see {@link #MEDALLION_OPS}.
	 */
	private static final String[] SWAP_ACTIONS = {"Wear", "Fill", "Empty", "Check", "Drop", "Examine", "Use"};
	private static final String SWAP_TARGET = "<col=ff9040>Rune pouch";
	private static final int SWAP_SHIFT_ROW = 2;
	private static final int SWAP_LEFT_ROW = 3;

	/**
	 * The live trace's root (683,420 253x10 rows) with its two Swap rows. Each Swap child has
	 * the same ops (both modes leaving out the same one), the item as the parent's target, and
	 * its own click callbacks. Neither child has bounds yet.
	 */
	private static NativeModel swapModel(boolean opensLeft, int[] clicks)
	{
		int rootX = opensLeft ? 683 : 2;
		NativeModel model = new NativeModel(rootX, 420, 253, 10, false, 0, entry -> entry == 6 || entry == 7);
		for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
		{
			int entryIndex = model.rows - 1 - row;
			String option = row == SWAP_SHIFT_ROW ? "Swap shift-click" : "Swap left-click";
			model.rootEntryOptions[entryIndex] = option;
			NativeMenu child = model.childAtRow(row);
			child.entries = actions(SWAP_ACTIONS, e -> clicks[0]++);
			model.root.entries[entryIndex] = actionEntry(option, SWAP_TARGET, MenuAction.RUNELITE, child,
				e -> clicks[0]++);
		}
		model.live = new Point(rootX + 20, 490); // over root row 3, Swap left-click
		return model;
	}

	/** Entries bottom-up, so {@code options[0]} is the top row; every entry shares {@code callback}. */
	private static MenuEntry[] actions(String[] options, java.util.function.Consumer<MenuEntry> callback)
	{
		MenuEntry[] entries = new MenuEntry[options.length];
		for (int i = 0; i < options.length; i++)
		{
			entries[options.length - 1 - i] = actionEntry(options[i], "", MenuAction.RUNELITE, null, callback);
		}
		return entries;
	}

	/** Where native places either Swap child in the live trace: clamped to the canvas bottom. */
	private static void placeSwapChild(NativeModel model, int row, boolean opensLeft)
	{
		model.childAtRow(row).setBounds(opensLeft ? 552 : model.root.x + model.root.width, 465, 131, 127);
	}

	/**
	 * The proven frame (the closed shift-click child retained at a distinct rectangle on the same
	 * side, so the pixels identify the left-click child) next to the ambiguous one (the
	 * shift-click child reopened at the very same rectangle, as at 23:20:05). The root's arrow
	 * side follows retained geometry, so it is the same in both frames.
	 */
	private Frame[] provenThenShared(NativeModel model, Harness harness, boolean opensLeft) throws Exception
	{
		placeSwapChild(model, SWAP_LEFT_ROW, opensLeft);
		placeSwapChild(model, SWAP_SHIFT_ROW, opensLeft);
		model.childAtRow(SWAP_SHIFT_ROW).y = 280;
		model.open = model.childAtRow(SWAP_LEFT_ROW);
		Frame proven = harness.frame();
		placeSwapChild(model, SWAP_SHIFT_ROW, opensLeft);
		model.open = model.childAtRow(SWAP_SHIFT_ROW);
		Frame shared = harness.frame();
		return new Frame[]{proven, shared};
	}

	@Test
	public void liveSwapSiblingsSharingARectangleAreThemedWhenTheyRenderIdentically() throws Exception
	{
		for (boolean opensLeft : new boolean[]{true, false})
		{
			String context = "opensLeft=" + opensLeft;
			int[] clicks = new int[1];
			NativeModel model = swapModel(opensLeft, clicks);
			assertNotEquals(context + ": the siblings have different callbacks",
				model.childAtRow(SWAP_SHIFT_ROW).entries[0].onClick(),
				model.childAtRow(SWAP_LEFT_ROW).entries[0].onClick());
			Harness harness = harness(model);
			Frame[] frames = provenThenShared(model, harness, opensLeft);
			assertThemedNativeState(context + " proven", frames[0], model);
			assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_CHILD));
			assertThemedNativeState(context + " shared rectangle", frames[1], model);
			assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
			assertTrue(context + ": the ambiguous frame shows exactly what the proven frame showed",
				sameImage(frames[0].output, frames[1].output));

			// Native switches back without the pointer moving: the public state is unchanged.
			model.open = model.childAtRow(SWAP_LEFT_ROW);
			Frame back = harness.frame();
			assertThemedNativeState(context + " switched back", back, model);
			assertTrue(context, sameImage(frames[0].output, back.output));

			// Menu Entry Swapper rebuilds an equivalent parent entry with a new callback.
			int shiftEntry = model.rows - 1 - SWAP_SHIFT_ROW;
			model.root.entries[shiftEntry] = actionEntry("Swap shift-click", SWAP_TARGET, MenuAction.RUNELITE,
				model.childAtRow(SWAP_SHIFT_ROW), e -> clicks[0]++);
			model.open = model.childAtRow(SWAP_SHIFT_ROW);
			Frame rebuilt = harness.frame();
			assertThemedNativeState(context + " rebuilt parent", rebuilt, model);
			assertTrue(context, sameImage(frames[0].output, rebuilt.output));

			assertEquals(context + ": no frame fell back", 0L,
				outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
			assertEquals(context + ": three equivalent frames", 3L,
				outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
			assertEquals(context + ": rendering never runs a click callback", 0, clicks[0]);
		}
	}

	@Test
	public void rootHoverFollowsThePointerNeverTheChildNativeDrew() throws Exception
	{
		for (boolean opensLeft : new boolean[]{true, false})
		{
			NativeModel model = swapModel(opensLeft, new int[1]);
			placeSwapChild(model, SWAP_LEFT_ROW, opensLeft);
			placeSwapChild(model, SWAP_SHIFT_ROW, opensLeft);
			Harness harness = harness(model);
			int rootX = model.root.x;
			Point[] pointers = {
				new Point(rootX + 20, 439 + 15 * SWAP_SHIFT_ROW + 7), // Swap shift-click row
				new Point(rootX + 20, 439 + 15 * SWAP_LEFT_ROW + 7), // Swap left-click row
				new Point(opensLeft ? 600 : 300, 540), // inside the shared child panel
			};
			BufferedImage[] byPointer = new BufferedImage[pointers.length];
			for (int p = 0; p < pointers.length; p++)
			{
				model.live = pointers[p];
				BufferedImage first = null;
				for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
				{
					model.open = model.childAtRow(row);
					Frame frame = harness.frame();
					String context = "opensLeft=" + opensLeft + " pointer=" + p + " open row=" + row;
					assertThemedNativeState(context, frame, model);
					if (first == null)
					{
						first = frame.output;
					}
					else
					{
						assertTrue(context + ": which child is open never changes a pixel",
							sameImage(first, frame.output));
					}
				}
				byPointer[p] = first;
			}
			assertFalse("the shift-click row highlights under the pointer", sameImage(byPointer[0], byPointer[1]));
			assertFalse("a pointer in the child highlights no root row", sameImage(byPointer[1], byPointer[2]));
			assertEquals(0L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
		}
	}

	private interface SwapVariant
	{
		void apply(NativeModel model, int[] clicks);
	}

	@Test
	public void sharedRectangleWithAnyVisibleDifferenceStaysNative() throws Exception
	{
		java.util.Map<String, SwapVariant> variants = new java.util.LinkedHashMap<>();
		variants.put("different entry", (model, clicks) ->
			model.childAtRow(SWAP_SHIFT_ROW).entries[2] = actionEntry("Destroy", "", MenuAction.RUNELITE, null, null));
		variants.put("Reset in place of an op", (model, clicks) ->
			model.childAtRow(SWAP_SHIFT_ROW).entries[0] = actionEntry("Reset", "", MenuAction.RUNELITE, null, null));
		variants.put("extra Reset row", (model, clicks) ->
		{
			NativeMenu shift = model.childAtRow(SWAP_SHIFT_ROW);
			MenuEntry[] longer = Arrays.copyOf(shift.entries, shift.entries.length + 1);
			System.arraycopy(longer, 0, longer, 1, shift.entries.length);
			longer[0] = actionEntry("Reset", "", MenuAction.RUNELITE, null, null);
			shift.entries = longer;
		});
		variants.put("entry order", (model, clicks) ->
		{
			MenuEntry[] entries = model.childAtRow(SWAP_SHIFT_ROW).entries;
			MenuEntry top = entries[entries.length - 1];
			entries[entries.length - 1] = entries[entries.length - 2];
			entries[entries.length - 2] = top;
		});
		variants.put("colour tag", (model, clicks) ->
			model.childAtRow(SWAP_SHIFT_ROW).entries[3] = actionEntry("<col=ff0000>Check", "", MenuAction.RUNELITE, null, null));
		variants.put("icon tag", (model, clicks) ->
			model.childAtRow(SWAP_SHIFT_ROW).entries[3] = actionEntry("<img=1>Check", "", MenuAction.RUNELITE, null, null));
		variants.put("target text", (model, clicks) ->
			model.childAtRow(SWAP_SHIFT_ROW).entries[3] = actionEntry("Check", "Rune pouch", MenuAction.RUNELITE, null, null));
		variants.put("target kind", (model, clicks) ->
		{
			model.childAtRow(SWAP_SHIFT_ROW).entries[3] = actionEntry("Check", "Rune pouch",
				MenuAction.GAME_OBJECT_FIRST_OPTION, null, null);
			model.childAtRow(SWAP_LEFT_ROW).entries[3] = actionEntry("Check", "Rune pouch", MenuAction.RUNELITE, null, null);
		});
		variants.put("header", (model, clicks) ->
		{
			int entry = model.rows - 1 - SWAP_SHIFT_ROW;
			model.root.entries[entry] = actionEntry("Swap shift-click", "<col=ff9040>Rune pouch (full)",
				MenuAction.RUNELITE, model.childAtRow(SWAP_SHIFT_ROW), null);
		});
		variants.put("submenu arrow", (model, clicks) ->
		{
			NativeMenu dormant = new NativeMenu(100);
			dormant.entries = actions(new String[]{"Deeper"}, null);
			model.childAtRow(SWAP_SHIFT_ROW).entries[3] = actionEntry("Check", "", MenuAction.RUNELITE, dormant, null);
		});
		variants.put("submenu arrow side", (model, clicks) ->
		{
			// Both rows open a closed grandchild, one to each side (only possible above the root).
			for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
			{
				NativeMenu grandchild = new NativeMenu(100);
				grandchild.entries = actions(new String[]{"Deeper"}, null);
				grandchild.setBounds(row == SWAP_SHIFT_ROW ? 452 : 700, 300, 100, 37);
				model.childAtRow(row).entries[3] = actionEntry("Check", "", MenuAction.RUNELITE, grandchild, null);
			}
		});
		for (java.util.Map.Entry<String, SwapVariant> variant : variants.entrySet())
		{
			for (boolean opensLeft : new boolean[]{true, false})
			{
				if (variant.getKey().equals("submenu arrow side") && !opensLeft)
				{
					continue; // the grandchild positions above assume the left-opening root
				}
				String context = variant.getKey() + " opensLeft=" + opensLeft;
				int[] clicks = new int[1];
				NativeModel model = swapModel(opensLeft, clicks);
				variant.getValue().apply(model, clicks);
				placeSwapChild(model, SWAP_LEFT_ROW, opensLeft);
				placeSwapChild(model, SWAP_SHIFT_ROW, opensLeft);
				Harness harness = harness(model);
				for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
				{
					model.open = model.childAtRow(row);
					assertEquals(context + " open row=" + row + ": no wrong submenu is ever shown",
						Shown.NATIVE, harness.frame().shown);
				}
				assertEquals(context, 2L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
				assertEquals(context, 0L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
			}
		}
	}

	@Test
	public void equivalentSiblingsKeepEveryOpacityAndStyleSetting() throws Exception
	{
		for (boolean opensLeft : new boolean[]{true, false})
		{
			BufferedImage previous = null;
			for (int opacity : new int[]{0, 50, 100})
			{
				String context = "opacity=" + opacity + " opensLeft=" + opensLeft;
				NativeModel model = swapModel(opensLeft, new int[1]);
				Harness harness = harness(model);
				Theme base = harness.themes.effectiveTheme();
				harness.themes.setWorking(base.withOpacity(opacity).withHoverOpacity(opacity));
				Frame[] frames = provenThenShared(model, harness, opensLeft);
				assertEquals(context, Shown.THEMED, frames[0].shown);
				assertEquals(context, Shown.THEMED, frames[1].shown);
				assertEquals(context, 1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
				assertTrue(context + ": same pixels as the proven frame", sameImage(frames[0].output, frames[1].output));
				if (previous != null)
				{
					assertFalse(context + ": the opacity setting is applied", sameImage(previous, frames[1].output));
				}
				previous = frames[1].output;
			}
			NativeModel model = swapModel(opensLeft, new int[1]);
			Harness harness = harness(model);
			harness.config.textOffsetX = harness.config.textOffsetX + 4;
			Frame[] frames = provenThenShared(model, harness, opensLeft);
			assertThemedNativeState("text offset opensLeft=" + opensLeft, frames[1], model);
			assertTrue(sameImage(frames[0].output, frames[1].output));
		}
	}

	/** Swap children at one rectangle, each opening a grandchild at one rectangle to its left. */
	private static NativeModel nestedSwapModel(String[] shiftGrandchildOps)
	{
		NativeModel model = swapModel(true, new int[1]);
		for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
		{
			placeSwapChild(model, row, true);
			NativeMenu grandchild = new NativeMenu(120);
			grandchild.entries = actions(row == SWAP_SHIFT_ROW ? shiftGrandchildOps
				: new String[]{"Inventory", "Equipment", "Bank"}, e -> { });
			grandchild.setBounds(432, 465, 120, 67);
			model.childAtRow(row).entries[3] = actionEntry("Check", "", MenuAction.RUNELITE, grandchild,
				e -> { });
			model.grandchildren.add(grandchild);
		}
		model.live = new Point(470, 500); // inside the grandchild
		return model;
	}

	@Test
	public void nestedEquivalentChainsAreThemedAndNestedDifferencesStayNative() throws Exception
	{
		NativeModel model = nestedSwapModel(new String[]{"Inventory", "Equipment", "Bank"});
		Harness harness = harness(model);
		BufferedImage first = null;
		for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
		{
			model.open = model.childAtRow(row);
			model.openGrandchild = model.childAtRow(row).entries[3].getSubMenu() == null ? null
				: (NativeMenu) model.childAtRow(row).entries[3].getSubMenu();
			Frame frame = harness.frame();
			assertThemedNativeState("nested open row=" + row, frame, model);
			first = first == null ? frame.output : first;
			assertTrue("nested open row=" + row, sameImage(first, frame.output));
		}
		assertEquals(2L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));

		NativeModel different = nestedSwapModel(new String[]{"Inventory", "Equipment", "Bank-all"});
		Harness differentHarness = harness(different);
		different.open = different.childAtRow(SWAP_SHIFT_ROW);
		different.openGrandchild = (NativeMenu) different.childAtRow(SWAP_SHIFT_ROW).entries[3].getSubMenu();
		assertEquals("grandchild contents differ", Shown.NATIVE, differentHarness.frame().shown);
		assertEquals(1L, outcome(differentHarness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
	}

	@Test
	public void equivalentSiblingsThatCouldHideAGrandchildStayNative() throws Exception
	{
		NativeModel model = swapModel(true, new int[1]);
		for (int row : new int[]{SWAP_SHIFT_ROW, SWAP_LEFT_ROW})
		{
			placeSwapChild(model, row, true);
			NativeMenu grandchild = new NativeMenu(100);
			grandchild.entries = actions(new String[]{"Hidden"}, null);
			grandchild.setBounds(700, 470, 100, 37); // entirely over the root: drawn or not, no evidence
			model.childAtRow(row).entries[3] = actionEntry("Check", "", MenuAction.RUNELITE, grandchild, null);
		}
		model.open = model.childAtRow(SWAP_SHIFT_ROW);
		Harness harness = harness(model);
		assertEquals(Shown.NATIVE, harness.frame().shown);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
	}

	@Test
	public void scrolledEquivalentSiblingsAreThemedOnlyWhenAttachedToTheirRows() throws Exception
	{
		for (int childY : new int[]{300, 400})
		{
			NativeModel model = longLoggedRoot(0);
			for (int row : new int[]{20, 21})
			{
				int entry = model.rows - 1 - row;
				NativeMenu child = model.childAtRow(row);
				child.entries = actions(new String[]{"Wear", "Fill", "Empty", "Check", "Drop"}, e -> { });
				model.root.entries[entry] = actionEntry(row == 20 ? "Swap shift-click" : "Swap left-click",
					SWAP_TARGET, MenuAction.RUNELITE, child, e -> { });
				child.setBounds(646, childY, 220, NativeModel.nativeHeight(5));
			}
			model.open = model.childAtRow(21);
			model.live = new Point(549, 340);
			Harness harness = harness(model);
			Frame frame = harness.frame();
			if (childY == 300)
			{
				assertThemedNativeState("attached to both parent rows", frame, model);
				assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
			}
			else
			{
				assertEquals("detached: no single child is proven at that position", Shown.NATIVE, frame.shown);
				assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_SCROLLABLE));
			}
		}
	}

	// ---- Screen-edge clamping (Drakan's medallion, live 00:10:09) ----

	/**
	 * The medallion's ops. Menu Entry Swapper lists every op in both Swap submenus except the
	 * one currently set for that mode, so the two submenus have equal row counts (equal
	 * rectangles at a clamped edge) but, unless both modes use the same op, different rows.
	 */
	private static final String[] MEDALLION_OPS = {"Wear", "Ver Sinhaza", "Darkmeyer", "Slepe", "Lab", "Rub",
		"Drop", "Examine"};

	private static String[] without(String[] ops, String omitted)
	{
		List<String> kept = new ArrayList<>(Arrays.asList(ops));
		assertTrue(kept.remove(omitted));
		return kept.toArray(new String[0]);
	}

	/**
	 * The live root layout: Swap shift-click is entry 2 and Swap left-click entry 3 (rows 7 and 6
	 * of a 10-row root, rows 5 and 4 of an 8-row one). Both children are 131px wide, the width
	 * native gave them, and are positioned only by the model's native open step.
	 */
	private static NativeModel medallionModel(int rootX, int rootY, int rootRows, String[] ops,
		String leftOmits, String shiftOmits)
	{
		NativeModel model = new NativeModel(rootX, rootY, 253, rootRows, false, 0, entry -> entry == 2 || entry == 3);
		for (int entry : new int[]{2, 3})
		{
			boolean shift = entry == 2;
			NativeMenu child = new NativeMenu(131);
			child.entries = actions(without(ops, shift ? shiftOmits : leftOmits), e -> { });
			model.children.set(model.children.indexOf(model.childByEntry[entry]), child);
			model.childByEntry[entry] = child;
			model.rootEntryOptions[entry] = shift ? "Swap shift-click" : "Swap left-click";
			model.root.entries[entry] = actionEntry(model.rootEntryOptions[entry], "<col=ff9040>Drakan's medallion",
				MenuAction.RUNELITE, child, e -> { });
		}
		return model;
	}

	/** Hovers root {@code row} for the native open delay, as a player moving between the Swap rows. */
	private static void hoverRow(NativeModel model, int row)
	{
		model.live = new Point(model.root.x + 30, model.root.y + MenuRenderer.HEADER_HEIGHT + row * MenuRenderer.ROW_HEIGHT + 7);
		for (int i = 0; i < OPEN_DELAY_CYCLES; i++)
		{
			model.cycle();
		}
	}

	@Test
	public void screenEdgeMatrixIsolatesVerticalClampingAsTheTrigger() throws Exception
	{
		int tallHeight = NativeModel.nativeHeight(10);
		int shortHeight = NativeModel.nativeHeight(8);
		// {label, rootX, rootY, rootRows}: 696 opens left (as live), 400 opens right, 737 puts the
		// root against the right edge as well.
		Object[][] positions = {
			{"middle right tall", 400, 100, 10},
			{"bottom right tall", 400, CANVAS_HEIGHT - tallHeight, 10},
			{"middle left tall", 696, 100, 10},
			{"bottom left tall (live)", 696, CANVAS_HEIGHT - tallHeight, 10},
			{"bottom left short", 696, CANVAS_HEIGHT - shortHeight, 8},
			{"bottom corner tall", CANVAS_WIDTH - 253, CANVAS_HEIGHT - tallHeight, 10},
			{"middle left short", 696, 200, 8},
		};
		List<String> sharedCells = new ArrayList<>();
		List<String> identifiedCells = new ArrayList<>();
		for (Object[] position : positions)
		{
			for (boolean sameOps : new boolean[]{false, true})
			{
				for (boolean oneRowChildren : new boolean[]{false, true})
				{
					String[] ops = oneRowChildren ? new String[]{"Wear", "Drop"} : MEDALLION_OPS;
					String leftOmits = "Wear";
					String shiftOmits = sameOps ? "Wear" : "Drop";
					if (oneRowChildren && sameOps)
					{
						continue; // nothing left to compare
					}
					String context = position[0] + (sameOps ? " same ops" : " different ops")
						+ (oneRowChildren ? " one-row children" : " seven-row children");
					NativeModel model = medallionModel((int) position[1], (int) position[2], (int) position[3],
						ops, leftOmits, shiftOmits);
					int rows = (int) position[3];
					int leftRow = rows - 1 - 3;
					int shiftRow = rows - 1 - 2;
					Harness harness = harness(model);

					hoverRow(model, leftRow);
					NativeMenu left = model.childAtRow(leftRow);
					assertEquals(context, left, model.open);
					assertThemedNativeState(context + ": first open, sibling dormant", harness.frame(), model);

					hoverRow(model, shiftRow);
					NativeMenu shift = model.childAtRow(shiftRow);
					assertEquals(context, shift, model.open);
					Rectangle leftBounds = left.bounds();
					boolean sharedRect = leftBounds.equals(shift.bounds());
					// Native aligns a child's header with its parent row: naturalY = rowTop - 19.
					int leftNaturalY = model.root.y + leftRow * MenuRenderer.ROW_HEIGHT;
					int shiftNaturalY = model.root.y + shiftRow * MenuRenderer.ROW_HEIGHT;
					boolean bothClamped = leftNaturalY + leftBounds.height > CANVAS_HEIGHT
						&& shiftNaturalY + shift.bounds().height > CANVAS_HEIGHT;
					assertEquals(context + ": siblings share a rectangle exactly when both are clamped to the bottom",
						bothClamped, sharedRect);
					boolean opensLeft = leftBounds.x < model.root.x;
					assertEquals(context + ": opening side", (int) position[1] != 400, opensLeft);

					// Rapid switching: shift, left, shift, then into the open child's panel.
					for (int step = 0; step < 4; step++)
					{
						if (step == 1)
						{
							hoverRow(model, leftRow);
						}
						else if (step == 2)
						{
							hoverRow(model, shiftRow);
						}
						else if (step == 3)
						{
							Rectangle open = model.open.bounds();
							model.live = new Point(open.x + open.width / 2, open.y + open.height - 10);
							model.cycle();
						}
						Rectangle[] before = {model.root.bounds(), left.bounds(), shift.bounds()};
						Frame frame = harness.frame();
						assertArrayEquals(context + ": rendering never moves a native menu",
							before, new Rectangle[]{model.root.bounds(), left.bounds(), shift.bounds()});
						String stepContext = context + " step " + step;
						assertThemedNativeState(stepContext, frame, model);
						if (sharedRect && !sameOps)
						{
							// Genuinely different rows at one rectangle: themed with the open child's
							// rows, exactly as when the probe resolves it alone.
							NativeMenu closed = model.open == left ? shift : left;
							assertTrue(stepContext + ": the open child's content, not its sibling's",
								sameImage(resolvedTwin(harness, closed).output, frame.output));
						}
					}
					if (sharedRect)
					{
						sharedCells.add(context);
					}
					if (sharedRect && !sameOps)
					{
						identifiedCells.add(context);
					}
					assertEquals(context, 0L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
					assertEquals(context, sharedRect && sameOps ? 4L : 0L,
						outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
					assertEquals(context, sharedRect && !sameOps ? 4L : 0L,
						outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD));
				}
			}
		}
		// Only the bottom-clamped seven-row cells share a rectangle, opening left or right; the
		// ones whose rows differ are told apart by the native pixels.
		assertEquals(Arrays.asList(
			"bottom right tall different ops seven-row children", "bottom right tall same ops seven-row children",
			"bottom left tall (live) different ops seven-row children", "bottom left tall (live) same ops seven-row children",
			"bottom left short different ops seven-row children", "bottom left short same ops seven-row children",
			"bottom corner tall different ops seven-row children", "bottom corner tall same ops seven-row children"),
			sharedCells);
		assertEquals(Arrays.asList(
			"bottom right tall different ops seven-row children", "bottom left tall (live) different ops seven-row children",
			"bottom left short different ops seven-row children", "bottom corner tall different ops seven-row children"),
			identifiedCells);
	}

	/**
	 * The same frame through a fresh overlay with the same theme and settings, but with the closed
	 * sibling moved off the shared rectangle (same side, so the root's arrows are unchanged): the
	 * probe then resolves the open child alone, so this is the image the open child must get.
	 */
	private Frame resolvedTwin(Harness original, NativeMenu closed) throws Exception
	{
		int y = closed.y;
		closed.y = 280;
		try
		{
			Harness twin = harness(original.model);
			twin.themes.setWorking(original.themes.effectiveTheme());
			twin.config.textOffsetX = original.config.textOffsetX;
			twin.config.showHitRows = original.config.showHitRows;
			Frame frame = twin.frame();
			assertEquals("the twin frame resolves the open child alone", 1L,
				outcome(twin, MenuOverlay.SubmenuOutcome.READY_WITH_CHILD));
			return frame;
		}
		finally
		{
			closed.y = y;
		}
	}

	/** Clamped children share a rectangle but contain different rows, so the frame stays native. */
	@Test
	public void screenEdgeAmbiguityKeepsTheNativeMenu() throws Exception
	{
		NativeModel model = medallionModel(696, CANVAS_HEIGHT - NativeModel.nativeHeight(10), 10,
			MEDALLION_OPS, "Wear", "Drop");
		hoverRow(model, 6);
		hoverRow(model, 7);
		SubmenuTree tree = SubmenuTree.capture(model.root);
		NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(model.pixels, CANVAS_WIDTH,
			CANVAS_HEIGHT, tree, model::nativeDraw);
		ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(model.root.bounds(), 10,
			CANVAS_WIDTH, CANVAS_HEIGHT, false, 0);
		StringBuilder why = new StringBuilder();
		assertEquals(null, MenuOverlay.visuallyEquivalentAlternatives(result, tree, viewport, model.live, 0, why));
		assertEquals(ExactCoverageResolver.State.AMBIGUOUS, result.exact.state);
		assertEquals(2, result.exact.alternatives.length);
		assertEquals(-1, result.exact.hiddenCandidate);
		// Candidate #0 is entry 2 (shift-click, omits Drop), #1 entry 3 (left-click, omits Wear).
		assertEquals("chains [0] and [1] differ: ROWS depth=1 row=0 option 'Wear' vs 'Ver Sinhaza'", why.toString());
		assertEquals(new Rectangle(696, 420, 253, 172), tree.nodes.get(0).bounds);
		assertEquals(new Rectangle(565, 465, 131, 127), tree.candidateBounds.get(0));
		assertEquals(tree.candidateBounds.get(0), tree.candidateBounds.get(1));
		assertEquals(CANVAS_HEIGHT, tree.candidateBounds.get(0).y + tree.candidateBounds.get(0).height);
		assertEquals(7, viewport.hoverIndex(model.live));
	}

	@Test
	public void scrolledRootChildrenClampedAtTheViewportBottom() throws Exception
	{
		for (boolean sameRows : new boolean[]{false, true})
		{
			NativeModel model = clampedSwapPair(sameRows);
			Harness harness = harness(model);
			hoverRow(model, 36);
			assertThemedNativeState("first open", harness.frame(), model);
			hoverRow(model, 37);
			assertEquals(model.childAtRow(36).bounds(), model.childAtRow(37).bounds());
			assertEquals(495, model.childAtRow(37).bounds().y);
			Frame frame = harness.frame();
			if (sameRows)
			{
				assertThemedNativeState("equivalent, attached to both rows", frame, model);
				assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
			}
			else
			{
				// The row-36 child was proven alone on first open, so its native image excludes it.
				assertThemedNativeState("different rows, identified natively", frame, model);
				assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD));
				assertEquals(0L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
				assertTrue("the row-37 child's content", sameImage(
					resolvedTwin(harness, model.childAtRow(36)).output, frame.output));
			}
		}
	}

	/**
	 * Rows 36 and 37 are the last visible rows of the long root; their 5-row children clamp to
	 * y=495 and so share one rectangle once both have opened. With {@code sameRows} the two
	 * children render identically (an equivalent pair); otherwise their last rows differ and
	 * only native pixels can tell them apart (an identified pair).
	 */
	private static NativeModel clampedSwapPair(boolean sameRows)
	{
		NativeModel model = longLoggedRoot(0);
		for (int row : new int[]{36, 37})
		{
			int entry = model.rows - 1 - row;
			NativeMenu child = new NativeMenu(220);
			child.entries = actions(sameRows || row == 36 ? new String[]{"Wear", "Fill", "Empty", "Check", "Drop"}
				: new String[]{"Wear", "Fill", "Empty", "Check", "Use"}, e -> { });
			model.children.set(model.children.indexOf(model.childByEntry[entry]), child);
			model.childByEntry[entry] = child;
			model.root.entries[entry] = actionEntry(row == 36 ? "Swap left-click" : "Swap shift-click",
				SWAP_TARGET, MenuAction.RUNELITE, child, e -> { });
		}
		return model;
	}

	// ---- Native pixel identification at the live screen edge (canvas 592, children 565,465,131x127) ----

	private static final Rectangle LIVE_SHARED = new Rectangle(565, 465, 131, 127);

	private static NativeModel liveMedallion()
	{
		return medallionModel(696, CANVAS_HEIGHT - NativeModel.nativeHeight(10), 10, MEDALLION_OPS, "Wear", "Drop");
	}

	private static Point onRootRow(NativeModel model, int row)
	{
		return new Point(model.root.x + 30, model.root.y + MenuRenderer.HEADER_HEIGHT + row * MenuRenderer.ROW_HEIGHT + 7);
	}

	/** Whether the native draw paints a yellow (hovered) row in {@code menu}; the framebuffer is restored. */
	private static boolean nativeHoverIn(NativeModel model, NativeMenu menu)
	{
		int[] saved = model.pixels.clone();
		model.nativeDraw();
		boolean yellow = false;
		Rectangle r = menu.bounds();
		for (int y = r.y; y < r.y + r.height; y++)
		{
			for (int x = r.x; x < r.x + r.width; x++)
			{
				yellow |= model.pixels[y * CANVAS_WIDTH + x] == 0xFFFFFF00;
			}
		}
		System.arraycopy(saved, 0, model.pixels, 0, saved.length);
		return yellow;
	}

	@Test
	public void liveScreenEdgeSiblingsAreIdentifiedByTheNativeDraw() throws Exception
	{
		for (int opacity : new int[]{100, 50, 0})
		{
			String context = "opacity=" + opacity;
			NativeModel model = liveMedallion();
			Harness harness = harness(model);
			harness.themes.setWorking(harness.themes.effectiveTheme().withOpacity(opacity).withHoverOpacity(opacity));
			NativeMenu left = model.childAtRow(6);
			NativeMenu shift = model.childAtRow(7);

			hoverRow(model, 6);
			assertThemedNativeState(context + ": first open, sibling dormant", harness.frame(), model);
			assertEquals(LIVE_SHARED, left.bounds());
			assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_CHILD));

			// The open delay: one cycle over the shift row, native still draws the left child.
			model.live = onRootRow(model, 7);
			model.cycle();
			assertEquals(left, model.open);
			Frame pending = harness.frame();
			assertThemedNativeState(context + ": pending, sibling still dormant", pending, model);

			model.cycle();
			assertEquals(shift, model.open);
			assertEquals(LIVE_SHARED, shift.bounds());
			Frame opened = harness.frame();
			assertThemedNativeState(context + ": shift opened at the shared rectangle", opened, model);
			assertTrue(context + ": the shift child's rows", sameImage(resolvedTwin(harness, left).output, opened.output));
			assertFalse(context + ": the two children's themed images differ", sameImage(pending.output, opened.output));

			// The open delay back: the pointer is over the left row, native still draws shift.
			model.live = onRootRow(model, 6);
			model.cycle();
			assertEquals(shift, model.open);
			Frame delayed = harness.frame();
			assertThemedNativeState(context + ": delayed", delayed, model);
			assertTrue(context + ": still the shift child, hover on the left row",
				sameImage(resolvedTwin(harness, left).output, delayed.output));

			model.cycle();
			assertEquals(left, model.open);
			Frame back = harness.frame();
			assertThemedNativeState(context + ": back to left", back, model);
			assertTrue(context + ": the left child's rows", sameImage(resolvedTwin(harness, shift).output, back.output));

			// Native switches with the pointer unchanged (the live 23:20:05 frame).
			model.open = shift;
			Frame same = harness.frame();
			assertThemedNativeState(context + ": same-pointer switch", same, model);
			assertTrue(context, sameImage(resolvedTwin(harness, left).output, same.output));

			// The pointer inside the open child: native turns that row yellow.
			model.live = new Point(shift.x + 60, shift.y + 31 + 15 * 3 - 5);
			model.cycle();
			assertEquals(shift, model.open);
			assertTrue(context + ": the native draw highlights a child row", nativeHoverIn(model, shift));
			Frame inside = harness.frame();
			assertThemedNativeState(context + ": pointer inside the child", inside, model);
			assertTrue(context, sameImage(resolvedTwin(harness, left).output, inside.output));

			assertEquals(context, 0L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
			assertEquals(context, 5L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD));
		}
	}

	@Test
	public void nativePixelsThatDoNotExcludeEveryOtherSiblingStayNative() throws Exception
	{
		// No proven image: both children share the rectangle from the first frame.
		NativeModel model = liveMedallion();
		Harness harness = harness(model);
		model.childAtRow(6).setBounds(LIVE_SHARED.x, LIVE_SHARED.y, LIVE_SHARED.width, LIVE_SHARED.height);
		model.childAtRow(7).setBounds(LIVE_SHARED.x, LIVE_SHARED.y, LIVE_SHARED.width, LIVE_SHARED.height);
		model.live = onRootRow(model, 7);
		model.drawMouse = model.live;
		for (int row : new int[]{7, 6, 7})
		{
			model.open = model.childAtRow(row);
			assertEquals("never proven, row " + row, Shown.NATIVE, harness.frame().shown);
		}
		assertEquals(3L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));

		// Only the drawn child is remembered: its image matches, the other has none, so either fits.
		model = liveMedallion();
		harness = harness(model);
		hoverRow(model, 6);
		assertThemedNativeState("proven", harness.frame(), model);
		model.childAtRow(7).setBounds(LIVE_SHARED.x, LIVE_SHARED.y, LIVE_SHARED.width, LIVE_SHARED.height);
		assertEquals(model.childAtRow(6), model.open);
		assertEquals("the remembered child matches, the other is unknown", Shown.NATIVE, harness.frame().shown);

		// A native draw that no longer matches the remembered images (its header differs).
		model = liveMedallion();
		harness = harness(model);
		hoverRow(model, 6);
		assertThemedNativeState("proven", harness.frame(), model);
		hoverRow(model, 7);
		assertThemedNativeState("identified", harness.frame(), model);
		model.glyphSalt = 1;
		hoverRow(model, 6);
		assertEquals("stale images", Shown.NATIVE, harness.frame().shown);
		model.glyphSalt = 0;
		assertThemedNativeState("matching again", harness.frame(), model);

		// The dangerous form: the drawn child's image is stale and its sibling has none, so
		// elimination alone would pick the sibling. The differing header rejects the image.
		model = liveMedallion();
		harness = harness(model);
		hoverRow(model, 6);
		assertThemedNativeState("proven", harness.frame(), model);
		model.childAtRow(7).setBounds(LIVE_SHARED.x, LIVE_SHARED.y, LIVE_SHARED.width, LIVE_SHARED.height);
		model.glyphSalt = 1;
		assertEquals(model.childAtRow(6), model.open);
		assertEquals("a stale image never excludes the drawn child", Shown.NATIVE, harness.frame().shown);
		assertEquals(0L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD));

		// A new menu opening forgets every image.
		harness.overlay.menuOpened();
		assertEquals("forgotten with the opening", Shown.NATIVE, harness.frame().shown);
		assertEquals(0, ((NativeChildImages) field(harness.overlay, "nativeChildImages")).size());

		// A sibling whose contents changed since its image was taken has no image, so it is not
		// excluded, and the drawn child's matching image alone proves nothing.
		model = liveMedallion();
		harness = harness(model);
		hoverRow(model, 6);
		assertThemedNativeState("proven", harness.frame(), model);
		hoverRow(model, 7);
		assertThemedNativeState("identified", harness.frame(), model);
		model.childAtRow(7).entries[0] = actionEntry("Lab", "", MenuAction.RUNELITE, null, e -> { });
		hoverRow(model, 6);
		assertEquals("the sibling's changed contents have no image", Shown.NATIVE, harness.frame().shown);
	}

	/** The plain flat-colour draw carries no row text, so nothing tells the siblings apart. */
	@Test
	public void identicalNativePixelsNeverIdentifyASibling() throws Exception
	{
		NativeModel model = liveMedallion();
		model.plainNativeDraw = true;
		Harness harness = harness(model);
		hoverRow(model, 6);
		assertThemedNativeState("proven", harness.frame(), model);
		hoverRow(model, 7);
		assertEquals("the left image matches the shift child's flat pixels", Shown.NATIVE, harness.frame().shown);
		hoverRow(model, 6);
		assertEquals(Shown.NATIVE, harness.frame().shown);
		assertEquals(0L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD));
	}

	@Test
	public void overlappingStaleRectanglesNeverDrawAClosedChild() throws Exception
	{
		// Seven retained children at one position whose widths differ by 7px, and two more
		// overlapping them lower down. Each in turn is the open one.
		int[] widths = {196, 203, 210, 217, 224, 238, 245};
		for (int open = 0; open < widths.length; open++)
		{
			NativeModel model = longLoggedRoot(0);
			for (int i = 0; i < widths.length; i++)
			{
				model.retain(14 + i, 646, 230, widths[i]);
			}
			model.retain(25, 646, 330, 230);
			model.retain(26, 646, 345, 199);
			model.openLogged(14 + open, 646, 230, widths[open]);
			model.live = new Point(549, 22 + 15 * (14 + open) + 5);
			Frame frame = harness(model).frame();
			String context = "open width " + widths[open];
			assertThemedNativeState(context, frame, model);
		}
	}

	/** A 10-row root at x=100; root row 2's child at x=312 has grandchildren at x=512. */
	private static NativeModel nestedModel()
	{
		NativeModel model = new NativeModel(100, 0, 212, 10, false, 0, entry -> entry >= 2 && entry <= 7);
		model.openLogged(2, 312, 30, 200);
		model.live = new Point(400, 60);
		return model;
	}

	@Test
	public void nestedChainWithANearTieGrandchildIsThemedWhole() throws Exception
	{
		for (boolean wideOpen : new boolean[]{true, false})
		{
			NativeModel model = nestedModel();
			NativeMenu wide = model.nest(2, 0, 512, 30, 180, 97);
			NativeMenu narrow = model.nest(2, 1, 512, 30, 176, 97);
			model.openGrandchild = wideOpen ? wide : narrow;
			Frame frame = harness(model).frame();
			String context = (wideOpen ? "180px" : "176px") + " grandchild open";
			assertThemedNativeState(context, frame, model);
		}
		NativeModel childOnly = nestedModel();
		childOnly.nest(2, 0, 512, 30, 180, 97);
		childOnly.nest(2, 1, 512, 30, 176, 97);
		assertThemedNativeState("retained grandchildren, none open", harness(childOnly).frame(), childOnly);
	}

	@Test
	public void grandchildHiddenUnderItsParentIsNeverGuessed() throws Exception
	{
		NativeModel model = nestedModel();
		model.openGrandchild = model.nest(2, 0, 330, 40, 150, 60); // entirely under its parent
		Harness harness = harness(model);
		Frame frame = harness.frame();
		assertEquals("contained: drawn or not, the pixels are identical", Shown.NATIVE, frame.shown);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS));
	}

	/** The long root moved to x=700, so children open at x=912 and are clipped at 990. */
	private static NativeModel rightEdgeModel()
	{
		return new NativeModel(700, 0, 212, 76, true, 0, entry -> entry >= 2 && entry <= 72);
	}

	@Test
	public void clippedGeometryResolvesOnlyWhenTheDifferenceIsVisible() throws Exception
	{
		NativeModel hidden = rightEdgeModel();
		hidden.retain(20, 912, 300, 120);
		hidden.openLogged(21, 912, 300, 110); // both reach past x=990: same visible pixels
		hidden.live = new Point(800, 340);
		Harness hiddenHarness = harness(hidden);
		Frame hiddenFrame = hiddenHarness.frame();
		assertEquals(Shown.NATIVE, hiddenFrame.shown);
		assertEquals(MenuOverlay.ExactComparison.BOTH_UNRESOLVED, comparison(hiddenHarness));

		NativeModel visible = rightEdgeModel();
		visible.retain(20, 912, 300, 70);
		visible.openLogged(21, 912, 300, 60); // ends at x=972, inside the canvas
		visible.live = new Point(800, 340);
		Frame visibleFrame = harness(visible).frame();
		assertThemedNativeState("visible clipped difference", visibleFrame, visible);
	}

	@Test
	public void unpaintedPixelsInsideTheOpenChildAreNeverGuessed() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.unpainted = new Rectangle(646 + 40, 300, 4, 4); // below child 12: only child 15 marks it
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		Frame frame = harness.frame();
		assertEquals(MenuOverlay.ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED, comparison(harness));
		assertEquals("evidence against full coverage stays native", Shown.NATIVE, frame.shown);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_EXACT_UNRESOLVED));
	}

	@Test
	public void drawingOutsideTheOpenChainIsNeverGuessed() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.halo = new Rectangle(700, 200, 60, 4); // inside closed child 12's own area
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		Frame frame = harness.frame();
		assertEquals(Shown.NATIVE, frame.shown);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_EXACT_UNRESOLVED));
	}

	@Test
	public void geometryChangingDuringEveryProbeStaysNative() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		model.afterNativeDraw = () -> model.open.setBounds(model.open.x, model.open.y + 1,
			model.open.width, model.open.height);
		Harness harness = harness(model);
		Frame frame = harness.frame();
		assertEquals(Shown.NATIVE, frame.shown);
		assertEquals("one re-probe, then native", 2, frame.nativeDraws);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_GEOMETRY_CHANGED));
		assertEquals("evidence from a changed frame is never used", MenuOverlay.ExactComparison.OLD_FAILED,
			comparison(harness));
	}

	@Test
	public void bufferReplacedDuringTheProbeStaysNative() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		model.afterNativeDraw = () -> model.bufferOverride = new int[model.pixels.length];
		Harness harness = harness(model);
		Frame frame = harness.frame();
		assertEquals(Shown.NATIVE, frame.shown);
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_BUFFER_MISMATCH));
	}

	@Test
	public void contradictoryResolvedChainsAreLoggedAndNeverRendered() throws Exception
	{
		// With consistent pixels the two resolvers cannot prove different chains (an exclusive
		// pixel of one child is never inside another), so the contradiction is injected into a
		// real probe result and taken through the gate and the overlay's own bookkeeping.
		ExactCoverageResolverTest.Fixture f = new ExactCoverageResolverTest.Fixture(100, 4);
		f.child(0).setBounds(328, 100, 220, 97);
		f.child(1).setBounds(328, 300, 220, 97);
		NativeSubmenuProbe.Result result = f.probe(ExactCoverageResolverTest.Paint.OPAQUE, f.child(1));
		assertArrayEquals(new int[]{1}, result.activeChain);
		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.RESOLVED,
			new int[]{0}, 1, new int[]{0}, result.exact.changedPixels);
		SubmenuTree tree = SubmenuTree.capture(f.root);
		assertEquals(MenuOverlay.ExactComparison.CONTRADICTORY, MenuOverlay.classifyExact(result));
		assertEquals(MenuOverlay.SubmenuOutcome.FALLBACK_EXACT_CONTRADICTION,
			MenuOverlay.authoritativeRejection(result, tree));

		Harness harness = harness(longLoggedRoot(0));
		java.lang.reflect.Method record = MenuOverlay.class.getDeclaredMethod("exactRecord",
			NativeSubmenuProbe.Result.class, SubmenuTree.class, MenuOverlay.ExactComparison.class);
		record.setAccessible(true);
		for (int i = 0; i < 25; i++)
		{
			record.invoke(harness.overlay, result, tree, MenuOverlay.ExactComparison.CONTRADICTORY);
		}
		assertEquals(25L, ((long[]) field(harness.overlay, "exactComparisons"))
			[MenuOverlay.ExactComparison.CONTRADICTORY.ordinal()]);
		assertEquals("logged prominently up to the window limit", 25L, field(harness.overlay, "exactContradictionLogs"));
		assertEquals("then counted, never silent", 5L, field(harness.overlay, "exactContradictionSuppressed"));
	}

	/** Scrolls the long root up and down under a parked pointer, one row every three frames. */
	private ScrollRun longScroll(int sweeps) throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.live = new Point(549, 330); // root row 20 + scroll: native keeps opening there
		Harness harness = harness(model);
		ScrollRun run = new ScrollRun();
		List<Integer> wave = new ArrayList<>();
		for (int sweep = 0; sweep < sweeps; sweep++)
		{
			for (int scroll = 0; scroll < 38; scroll++)
			{
				wave.add(scroll);
			}
			for (int scroll = 38; scroll > 0; scroll--)
			{
				wave.add(scroll);
			}
		}
		for (int scroll : wave)
		{
			model.scroll = scroll;
			for (int step = 0; step < 3; step++)
			{
				model.cycle();
				Frame frame = harness.frame();
				String context = "frame " + run.frames
					+ " scroll=" + scroll + " open=" + model.openBounds();
				assertNeverWrong(context, frame, model);
				assertEquals(context + ": one native probe draw", 1, frame.nativeDraws);
				MenuOverlay.ExactComparison comparison = comparison(harness);
				run.comparisons[comparison.ordinal()]++;
				if (frame.shown == Shown.THEMED)
				{
					run.themed++;
				}
				else
				{
					run.fallbacks++;
					MenuOverlay.SubmenuOutcome outcome =
						(MenuOverlay.SubmenuOutcome) field(harness.overlay, "lastOutcome");
					run.fallbackOutcomes.merge(outcome.name(), 1, Integer::sum);
					assertTrue(context + ": native only when exact coverage did not resolve: "
						+ outcome + " " + comparison,
						outcome == MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS
							|| outcome == MenuOverlay.SubmenuOutcome.FALLBACK_EXACT_UNRESOLVED);
				}
				run.frames++;
			}
		}
		run.openChanges = model.openChanges;
		run.retained = model.retainedCount();
		return run;
	}

	private static final class ScrollRun
	{
		private int frames;
		private int themed;
		private int fallbacks;
		private int openChanges;
		private int retained;
		private final int[] comparisons = new int[MenuOverlay.ExactComparison.values().length];
		private final java.util.Map<String, Integer> fallbackOutcomes = new java.util.TreeMap<>();

		private int count(MenuOverlay.ExactComparison comparison)
		{
			return comparisons[comparison.ordinal()];
		}

		@Override
		public String toString()
		{
			StringBuilder text = new StringBuilder("frames=" + frames + " themed=" + themed + " native=" + fallbacks
				+ " openChanges=" + openChanges + " retained=" + retained + " fallbacks=" + fallbackOutcomes
				+ " comparisons:");
			for (MenuOverlay.ExactComparison comparison : MenuOverlay.ExactComparison.values())
			{
				text.append(' ').append(comparison).append('=').append(count(comparison));
			}
			return text.toString();
		}
	}

	@Test
	public void longScrollingSessionNeverDrawsAnUnprovenChildOrBlanks() throws Exception
	{
		ScrollRun run = longScroll(3);
		assertTrue("prolonged scrolling: " + run, run.frames > 600);
		assertTrue("native keeps switching children: " + run, run.openChanges > 100);
		assertTrue("many stale rectangles accumulate: " + run, run.retained > 30);
		assertEquals("never contradictory: " + run, 0, run.count(MenuOverlay.ExactComparison.CONTRADICTORY));
		assertEquals("every frame native fell back on was one exact coverage left unresolved: " + run,
			run.count(MenuOverlay.ExactComparison.BOTH_UNRESOLVED)
				+ run.count(MenuOverlay.ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED), run.fallbacks);
	}

	private static MenuOverlay.ExactComparison comparison(Harness harness) throws Exception
	{
		return (MenuOverlay.ExactComparison) field(harness.overlay, "lastExactComparison");
	}

	private static long outcome(Harness harness, MenuOverlay.SubmenuOutcome outcome) throws Exception
	{
		return ((long[]) field(harness.overlay, "submenuOutcomes"))[outcome.ordinal()];
	}

	// ---- Image cache (cache-on mode only, except where noted) ----

	@Test
	public void unchangedFramesReuseTheImage() throws Exception
	{
		assumeTrue("image cache mode", imageCache);
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(700, 260); // parked inside the open child
		Harness harness = harness(model);
		Frame first = harness.frame();
		assertThemedNativeState("first frame", first, model);
		assertEquals(SubmenuImageCache.Miss.EMPTY, harness.lastMiss());
		for (int i = 0; i < 10; i++)
		{
			Frame frame = harness.frame();
			assertThemedNativeState("frame " + i, frame, model);
			assertEquals("frame " + i, SubmenuImageCache.Miss.HIT, harness.lastMiss());
			assertTrue("frame " + i + ": the reused image is the same picture", sameImage(first.output, frame.output));
		}
		assertEquals("every reused frame was verified against a fresh render", 10L,
			field(harness.overlay, "imageVerifyChecks"));
		assertEquals("the probe still ran on every frame", 11, model.nativeDraws);
	}

	/** One step of a scripted session: a change, then the miss it must cause. */
	private interface Step
	{
		void apply(NativeModel model, Harness uncached, Harness cached) throws Exception;
	}

	@Test
	public void cachedFramesEqualUncachedFramesThroughEveryChange() throws Exception
	{
		assumeTrue("image cache mode", imageCache);
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250); // root row 15
		Harness uncached = new Harness(model, -1);
		Harness cached = new Harness(model, 0); // unverified: only the key decides
		Object[][] script = {
			{"first frame", SubmenuImageCache.Miss.EMPTY, (Step) (m, u, c) -> { }},
			{"nothing changed", SubmenuImageCache.Miss.HIT, (Step) (m, u, c) -> { }},
			{"mouse moves within the hovered row", SubmenuImageCache.Miss.HIT,
				(Step) (m, u, c) -> m.live = new Point(560, 252)},
			{"mouse moves to the next root row", SubmenuImageCache.Miss.HOVER,
				(Step) (m, u, c) -> m.live = new Point(560, 266)},
			{"mouse enters the open child", SubmenuImageCache.Miss.HOVER,
				(Step) (m, u, c) -> m.live = new Point(700, 260)},
			{"mouse moves to another child row", SubmenuImageCache.Miss.HOVER,
				(Step) (m, u, c) -> m.live = new Point(700, 290)},
			{"an entry's text changes in place", SubmenuImageCache.Miss.ROWS,
				(Step) (m, u, c) -> m.rootEntryOptions[60] = "Changed text"},
			{"an entry gains a colour tag", SubmenuImageCache.Miss.ROWS,
				(Step) (m, u, c) -> m.rootEntryOptions[60] = "<col=ff0000>Changed text"},
			{"the theme changes", SubmenuImageCache.Miss.THEME, (Step) (m, u, c) ->
			{
				Theme current = c.themes.effectiveTheme();
				Theme other = c.themes.builtIns().stream().filter(t -> t != current).findFirst().get();
				u.themes.setWorking(other);
				c.themes.setWorking(other);
			}},
			{"a config option changes", SubmenuImageCache.Miss.CONFIG, (Step) (m, u, c) ->
			{
				u.config.showHitRows = true;
				c.config.showHitRows = true;
			}},
			{"text offset changes", SubmenuImageCache.Miss.CONFIG, (Step) (m, u, c) ->
			{
				u.config.textOffsetX += 3;
				c.config.textOffsetX += 3;
			}},
			{"mod icons finish loading", SubmenuImageCache.Miss.ICONS,
				(Step) (m, u, c) -> m.modIcons = new net.runelite.api.IndexedSprite[0]},
			// A different region size reallocates the image, which empties the cache.
			{"native switches child (different width)", SubmenuImageCache.Miss.EMPTY,
				(Step) (m, u, c) -> m.openLogged(12, 646, 180, 196)},
			{"the root scrolls", SubmenuImageCache.Miss.SCROLL, (Step) (m, u, c) -> m.scroll = 1},
			{"the open child closes", SubmenuImageCache.Miss.EMPTY, (Step) (m, u, c) -> m.open = null},
			{"settled again", SubmenuImageCache.Miss.HIT, (Step) (m, u, c) -> { }},
		};
		for (Object[] step : script)
		{
			String name = (String) step[0];
			((Step) step[2]).apply(model, uncached, cached);
			Frame expected = uncached.frame();
			Frame actual = cached.frame();
			assertEquals(name, expected.shown, actual.shown);
			assertEquals(name, Shown.THEMED, actual.shown);
			assertEquals(name, step[1], cached.lastMiss());
			assertTrue(name + ": the cached frame is pixel-identical to an uncached render",
				sameImage(expected.output, actual.output));
		}
	}

	@Test
	public void sameGeometryDifferentChildIsNeverReused() throws Exception
	{
		assumeTrue("image cache mode", imageCache);
		// Two children with identical bounds but different rows: menu identity and geometry
		// alone would reuse the wrong picture.
		NativeModel model = longLoggedRoot(0);
		model.retain(14, 646, 230, 220);
		model.openLogged(15, 646, 230, 220);
		model.childAtRow(15).entries[0] = entry(new String[]{"Row fifteen's own entry"}, 0, null);
		model.live = new Point(549, 252);
		Harness uncached = new Harness(model, -1);
		Harness cached = new Harness(model, 0);
		cached.frame();
		uncached.frame();
		model.open = model.childAtRow(14); // native switched; the rectangles are identical
		Frame expected = uncached.frame();
		Frame actual = cached.frame();
		assertEquals("identical rectangles are ambiguous, so this frame stays native",
			expected.shown, actual.shown);
		model.retain(14, 646, 230, 214); // now distinguishable, same region
		model.openLogged(14, 646, 230, 214);
		expected = uncached.frame();
		actual = cached.frame();
		assertNotEquals(SubmenuImageCache.Miss.HIT, cached.lastMiss());
		assertTrue(sameImage(expected.output, actual.output));
	}

	@Test
	public void aFailedRenderIsNeverReused() throws Exception
	{
		assumeTrue("image cache mode", imageCache);
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("first", harness.frame(), model);
		assertThemedNativeState("cached", harness.frame(), model);
		assertEquals(SubmenuImageCache.Miss.HIT, harness.lastMiss());
		// The key cannot be read and the render then throws: native this frame.
		harness.config.throwOnHighlights = 2;
		harness.config.showHitRows = true;
		Frame failed = harness.frame();
		assertEquals(Shown.NATIVE, failed.shown);
		assertEquals(SubmenuImageCache.Miss.UNCACHEABLE, harness.lastMiss());
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.FALLBACK_RENDER_EXCEPTION));
		// Back to the earlier inputs: the half-drawn image must not be reused.
		harness.config.showHitRows = false;
		Frame recovered = harness.frame();
		assertThemedNativeState("recovered", recovered, model);
		assertEquals(SubmenuImageCache.Miss.EMPTY, harness.lastMiss());
	}

	@Test
	public void verificationCatchesAStaleImageAndShowsAFreshOne() throws Exception
	{
		assumeTrue("image cache mode", imageCache);
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness uncached = new Harness(model, -1);
		Harness cached = new Harness(model, 1);
		cached.failOnVerifyMismatch = false; // this test provokes the mismatch on purpose
		cached.frame();
		// Simulate an input the key forgot: the buffered image no longer matches its inputs.
		BufferedImage image = (BufferedImage) field(cached.overlay, "submenuImage");
		image.setRGB(image.getWidth() / 2, image.getHeight() / 2, 0xFFFF00FF);
		Frame expected = uncached.frame();
		Frame actual = cached.frame();
		assertEquals(1L, field(cached.overlay, "imageVerifyMismatchTotal"));
		assertEquals("the key matched; verification then replaced the stale image",
			SubmenuImageCache.Miss.HIT, cached.lastMiss());
		assertThemedNativeState("verified frame", actual, model);
		assertTrue("the stale picture was replaced before the blit", sameImage(expected.output, actual.output));
	}

	@Test
	public void cacheOffNeverLooksUp() throws Exception
	{
		assumeTrue("cache off modes", !imageCache);
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		harness.frame();
		harness.frame();
		assertEquals(null, harness.lastMiss());
		long[] misses = (long[]) field(harness.overlay, "imageMisses");
		assertEquals(0L, Arrays.stream(misses).sum());
	}

	@Test
	public void mouseOnlyChangesAfterConsumptionCannotBlankTheFrame() throws Exception
	{
		Point[] afterConsumption = {
			new Point(549, 380), // another root row that opens a child
			new Point(549, 200), // root row 12, whose child is retained but closed
			new Point(700, 260), // inside the open child
			new Point(700, 190), // inside the retained child only
			new Point(100, 100), // outside every menu
			new Point(-1, -1),   // off canvas
			null,
		};
		for (Point moved : afterConsumption)
		{
			NativeModel model = longLoggedRoot(0);
			model.retain(12, 646, 180, 196);
			model.openLogged(15, 646, 225, 235);
			model.live = new Point(549, 287);
			Harness harness = harness(model);
			harness.afterPrepare = () ->
			{
				model.live = moved;
				// Keep moving during the final draw as well.
				model.beforeEachClientCall = () -> model.live = model.live == null ? new Point(1, 1)
					: new Point(model.live.getX() + 7, model.live.getY() + 11);
			};
			Frame frame = harness.frame();
			String context = "moved to " + moved;
			assertThemedNativeState(context, frame, model);
			assertEquals(context, 1L, field(harness.overlay, "submenuBlits"));
			assertEquals(context, 0L, field(harness.overlay, "submenuChangedDrops"));
		}
	}

	@Test
	public void structuralChangeAfterConsumptionIsStillDropped() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 287);
		Harness harness = harness(model);
		harness.afterPrepare = () -> model.rootEntryOptions[40] = "Changed";
		Frame frame = harness.frame();
		assertEquals("a consumed frame cannot fall back to native", Shown.BLANK, frame.shown);
		assertEquals(1L, field(harness.overlay, "submenuChangedDrops"));
		assertEquals(0, frame.renderReads);
		assertEquals("the next frame must return to native drawing", Shown.NATIVE, harness.frame().shown);
		assertEquals("native fallback persists after a consumed-frame failure", Shown.NATIVE, harness.frame().shown);
	}

	@Test
	public void anEarlierConsumerKeepsOwnershipOfTheMenu() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		Harness harness = harness(model);
		BeforeMenuRender event = new BeforeMenuRender();
		event.consume();
		int nativeDraws = model.nativeDraws;
		harness.plugin.onBeforeMenuRender(event);
		BufferedImage output = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = output.createGraphics();
		try
		{
			harness.overlay.render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		assertEquals("the other plugin's event is not probed", nativeDraws, model.nativeDraws);
		assertEquals("our overlay does not replace its image", 0, output.getRGB(650, 230));
		assertEquals("normal ownership returns on the next event", Shown.THEMED, harness.frame().shown);
	}

	// ---- After consumption: changes between BeforeMenuRender and the overlay's final draw ----

	/** Root entry 40 (row 35) opens a child; entries 0 and 1 (the bottom rows) are plain. */
	private static final int ROOT_ENTRY_WITH_CHILD = 40;

	/** A long root with row 15's child open and the pointer on root row 19. */
	private static NativeModel openRowFifteen()
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 287);
		return model;
	}

	/** Same option, target, type and child as before; only the click callback is new. */
	private static void replaceRootCallback(NativeModel model, int entry)
	{
		MenuEntry old = model.root.entries[entry];
		model.root.entries[entry] = actionEntry(old.getOption(), old.getTarget(), old.getType(),
			old.getSubMenu(), newCallback());
	}

	private static void replaceCallback(NativeMenu menu, int entry)
	{
		MenuEntry old = menu.entries[entry];
		menu.entries[entry] = actionEntry(old.getOption(), old.getTarget(), old.getType(),
			old.getSubMenu(), newCallback());
	}

	/** A distinct instance on every call (a non-capturing lambda would be reused). */
	private static java.util.function.Consumer<MenuEntry> newCallback()
	{
		return new java.util.function.Consumer<MenuEntry>()
		{
			@Override
			public void accept(MenuEntry entry)
			{
			}
		};
	}

	private static void assertDroppedAndNativeAfter(String context, Harness harness, Frame frame) throws Exception
	{
		assertEquals(context + ": a consumed frame cannot fall back to native", Shown.BLANK, frame.shown);
		assertEquals(context, 1L, field(harness.overlay, "submenuChangedDrops"));
		assertEquals(context, 0L, field(harness.overlay, "submenuEquivalentBlits"));
		assertEquals(context + ": the final draw never reads the mouse", 0, frame.renderReads);
		assertEquals(context + ": the final draw never draws natively", 1, frame.nativeDraws);
		harness.afterPrepare = () -> { };
		assertEquals(context + ": native fallback persists after a consumed-frame failure",
			Shown.NATIVE, harness.frame().shown);
	}

	@Test
	public void changesThatCannotChangeTheImageKeepTheConsumedFrame() throws Exception
	{
		String[] changes = {"root callback", "open child callback", "entries of a closed child"};
		for (int change = 0; change < changes.length; change++)
		{
			String context = changes[change];
			NativeModel model = openRowFifteen();
			Frame unchanged = harness(model).frame();
			Harness harness = harness(model);
			int which = change;
			harness.afterPrepare = () ->
			{
				if (which == 0)
				{
					replaceRootCallback(model, ROOT_ENTRY_WITH_CHILD);
				}
				else if (which == 1)
				{
					replaceCallback(model.open, 2);
				}
				else
				{
					model.childAtRow(20).entries[1] = entry(new String[]{"Renamed"}, 0, null);
				}
			};
			Frame frame = harness.frame();
			assertThemedNativeState(context, frame, model);
			assertTrue(context + ": the image is the unchanged frame's image",
				sameImage(unchanged.output, frame.output));
			assertEquals(context, 0L, field(harness.overlay, "submenuChangedDrops"));
			assertFalse(context, (boolean) field(harness.overlay, "submenuBlitFailed"));
			// Only the callbacks are part of the strict check; a closed child's rows never were.
			assertEquals(context, which < 2 ? 1L : 0L, field(harness.overlay, "submenuEquivalentBlits"));

			// Theming continues, including through the next native submenu transition.
			harness.afterPrepare = () -> { };
			hoverRow(model, 18);
			assertThemedNativeState(context + ": next open child", harness.frame(), model);
			assertTrue(context, model.open == model.childAtRow(18));
		}
	}

	@Test
	public void changesThatCouldChangeTheImageStillDropTheConsumedFrame() throws Exception
	{
		String[] changes = {
			"root option text",
			"open child option text",
			"callback and text together",
			"open child replaced by an identical-looking menu",
			"another child opens",
			"open child closes",
			"open child moves",
			"open child gains a row",
		};
		for (int change = 0; change < changes.length; change++)
		{
			String context = changes[change];
			NativeModel model = openRowFifteen();
			Harness harness = harness(model);
			int which = change;
			harness.afterPrepare = () ->
			{
				NativeMenu open = model.open;
				switch (which)
				{
					case 0:
						model.rootEntryOptions[ROOT_ENTRY_WITH_CHILD] = "Changed";
						break;
					case 1:
						open.entries[2] = entry(new String[]{"Changed"}, 0, null);
						break;
					case 2:
						replaceRootCallback(model, ROOT_ENTRY_WITH_CHILD);
						model.rootEntryOptions[ROOT_ENTRY_WITH_CHILD + 1] = "Changed";
						break;
					case 3:
						// Equal pixels, but a different native menu object: never assumed open.
						NativeMenu twin = new NativeMenu(open.openWidth);
						twin.entries = open.entries.clone();
						twin.setBounds(open.x, open.y, open.width, open.height);
						int opener = model.rows - 1 - 15;
						model.root.entries[opener] = entry(model.rootEntryOptions, opener, twin);
						break;
					case 4:
						model.openLogged(18, 646, 270, 235);
						break;
					case 5:
						open.setBounds(0, 0, 0, 0);
						model.open = null;
						break;
					case 6:
						open.setBounds(open.x, open.y + MenuRenderer.ROW_HEIGHT, open.width, open.height);
						break;
					default:
						MenuEntry[] grown = Arrays.copyOf(open.entries, open.entries.length + 1);
						grown[grown.length - 1] = entry(new String[]{"Extra"}, 0, null);
						open.entries = grown;
						break;
				}
			};
			assertDroppedAndNativeAfter(context, harness, harness.frame());
		}
	}

	@Test
	public void equivalentChildrenKeepTheImageOnlyWhileEveryAlternativeIsUnchanged() throws Exception
	{
		// {change to the child that is not open, the outcome}: either child may be native's.
		for (boolean visible : new boolean[]{false, true})
		{
			String context = visible ? "closed twin's text" : "closed twin's callback";
			NativeModel model = clampedSwapPair(true);
			Harness harness = harness(model);
			hoverRow(model, 36);
			assertThemedNativeState(context + ": first open", harness.frame(), model);
			hoverRow(model, 37);
			NativeMenu closedTwin = model.childAtRow(36);
			harness.afterPrepare = () ->
			{
				if (visible)
				{
					closedTwin.entries[0] = actionEntry("Changed", "", MenuAction.RUNELITE, null, newCallback());
				}
				else
				{
					replaceCallback(closedTwin, 0);
				}
			};
			Frame frame = harness.frame();
			assertEquals(context, 1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_EQUIVALENT_CHILD));
			if (visible)
			{
				assertDroppedAndNativeAfter(context, harness, frame);
			}
			else
			{
				assertThemedNativeState(context, frame, model);
				assertEquals(context, 1L, field(harness.overlay, "submenuEquivalentBlits"));
			}
		}
	}

	@Test
	public void nativelyIdentifiedChildIsNeverRecheckedAfterConsumption() throws Exception
	{
		NativeModel model = clampedSwapPair(false);
		Harness harness = harness(model);
		hoverRow(model, 36);
		assertThemedNativeState("first open", harness.frame(), model);
		hoverRow(model, 37);
		// Not even a callback: the pixels that told the pair apart are gone after consumption.
		harness.afterPrepare = () -> replaceCallback(model.childAtRow(37), 0);
		Frame frame = harness.frame();
		assertEquals(1L, outcome(harness, MenuOverlay.SubmenuOutcome.READY_WITH_NATIVE_IDENTIFIED_CHILD));
		assertDroppedAndNativeAfter("identified pair", harness, frame);
	}

	@Test
	public void liveHierarchyOutsideTheLimitsAfterConsumptionIsDropped() throws Exception
	{
		NativeModel model = openRowFifteen();
		Harness harness = harness(model);
		// The open child's first row now opens the root again: an identity cycle.
		harness.afterPrepare = () -> model.open.entries[0] =
			entry(new String[]{model.open.entries[0].getOption()}, 0, model.root);
		assertDroppedAndNativeAfter("submenu cycle", harness, harness.frame());
	}

	@Test
	public void exceptionWhileRecheckingIsAFailedDrawNotAThemedFrame() throws Exception
	{
		NativeModel model = openRowFifteen();
		boolean[] failing = {false};
		model.root.entries[1] = failingEntry(model.rootEntryOptions[1], failing);
		Harness harness = harness(model);
		assertThemedNativeState("before", harness.frame(), model);
		// Entry 0's callback ends the strict check; reading entry 1 again then throws.
		harness.afterPrepare = () ->
		{
			replaceRootCallback(model, 0);
			failing[0] = true;
		};
		Frame frame = harness.frame();
		assertEquals("a consumed frame cannot fall back to native", Shown.BLANK, frame.shown);
		assertTrue((boolean) field(harness.overlay, "submenuBlitFailed"));
		assertFalse((boolean) field(harness.overlay, "prepared"));
		assertEquals(0L, field(harness.overlay, "submenuEquivalentBlits"));
		failing[0] = false;
		harness.afterPrepare = () -> { };
		assertEquals("native fallback persists after a failed final draw", Shown.NATIVE, harness.frame().shown);
	}

	/** A plain entry whose option read throws while {@code failing[0]} is set. */
	private static MenuEntry failingEntry(String option, boolean[] failing)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getOption":
						if (failing[0])
						{
							throw new IllegalStateException("entry read failed");
						}
						return option;
					case "getTarget": return "";
					case "getType": return MenuAction.RUNELITE;
					case "getItemId": return -1;
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					default:
						Class<?> type = method.getReturnType();
						if (type == boolean.class) return false;
						if (type == int.class) return 0;
						return null;
				}
			});
	}

	@Test
	public void eventsWhileOverlaysCannotDrawAreNeverConsumed() throws Exception
	{
		// RuneLite's OverlayRenderer draws no overlay unless the client is logged in.
		for (boolean submenus : new boolean[]{true, false})
		{
			NativeModel model = submenus ? openRowFifteen()
				: new NativeModel(434, 130, 212, 4, false, 0, entry -> false);
			model.live = new Point(500, 180);
			Harness harness = harness(model);
			for (GameState state : new GameState[]{GameState.LOADING, GameState.CONNECTION_LOST,
				GameState.HOPPING, GameState.LOGIN_SCREEN})
			{
				model.gameState = state;
				String context = (submenus ? "submenus " : "plain ") + state;
				Frame frame = harness.eventFrame();
				assertEquals(context, Shown.NATIVE, frame.shown);
				assertEquals(context + ": not probed", 0, frame.nativeDraws);
				assertFalse(context, (boolean) field(harness.overlay, "prepared"));
			}
			model.gameState = GameState.LOGGED_IN;
			Frame loggedIn = harness.eventFrame();
			assertNotEquals("logged in, the menu is ours again", Shown.NATIVE, loggedIn.shown);
			if (submenus)
			{
				assertThemedNativeState("logged in", loggedIn, model);
			}
		}
	}

	@Test
	public void stoppedPluginNeverConsumesOrDrawsAStaleFrame() throws Exception
	{
		NativeModel model = openRowFifteen();
		Harness harness = harness(model);
		assertThemedNativeState("running", harness.eventFrame(), model);
		// shutDown on the EDT between this frame's consumption and its overlay draw.
		harness.afterPrepare = harness::stopPlugin;
		Frame stopped = harness.eventFrame();
		assertEquals("the frame was consumed before shutDown", 1, stopped.nativeDraws);
		assertEquals("no image is drawn after shutDown", 1L, field(harness.overlay, "submenuBlits"));
		assertFalse((boolean) field(harness.overlay, "prepared"));
		// A post that was already under way when the EventBus unregistered the plugin.
		harness.afterPrepare = () -> { };
		Frame late = harness.eventFrame();
		assertEquals("not consumed once stopped", Shown.NATIVE, late.shown);
		assertEquals("not probed once stopped", 0, late.nativeDraws);
		assertFalse((boolean) field(harness.overlay, "prepared"));
	}

	@Test
	public void nativeChildSwitchIsThemedOnTheCycleItHappens() throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 290); // row 18, still inside the open delay
		Harness harness = harness(model);
		NativeMenu rowFifteen = model.childAtRow(15);
		NativeMenu rowEighteen = model.childAtRow(18);
		for (int cycle = 1; cycle <= OPEN_DELAY_CYCLES + 1; cycle++)
		{
			model.cycle();
			Frame frame = harness.frame();
			assertThemedNativeState("cycle " + cycle, frame, model);
			assertTrue("cycle " + cycle, model.open == (cycle < OPEN_DELAY_CYCLES ? rowFifteen : rowEighteen));
		}
	}

	private void assertLoggedFrame(String name, ModelFactory factory, Point sample, Point moved)
		throws Exception
	{
		// After the event-start sample the pointer keeps travelling in the logged direction,
		// one pixel per client call, and reaches the logged validation position by the end
		// of the native probe draw.
		NativeModel raced = factory.create();
		raced.live = sample;
		int stepX = Integer.signum(moved.getX() - sample.getX());
		int stepY = Integer.signum(moved.getY() - sample.getY());
		raced.beforeEachClientCall = () ->
		{
			if (raced.liveReads > 0)
			{
				raced.live = new Point(raced.live.getX() + stepX, raced.live.getY() + stepY);
			}
		};
		raced.afterNativeDraw = () -> raced.live = moved;
		Harness racedHarness = harness(raced);
		Frame racedFrame = racedHarness.frame();
		assertThemedNativeState(name, racedFrame, raced);

		NativeModel still = factory.create();
		still.live = sample;
		Frame stillFrame = harness(still).frame();
		assertThemedNativeState(name + " still", stillFrame, still);
		assertTrue(name + ": movement after the sample must not change the frame",
			sameImage(racedFrame.output, stillFrame.output));

		NativeModel later = factory.create();
		later.live = moved;
		Frame laterFrame = harness(later).frame();
		assertThemedNativeState(name + " later sample", laterFrame, later);
		assertFalse(name + ": hover follows the event-start sample",
			sameImage(racedFrame.output, laterFrame.output));
	}

	/** The frame is themed, uses one mouse sample and one native probe, and draws native's chain. */
	private static void assertThemedNativeState(String context, Frame frame, NativeModel model)
	{
		assertEquals(context, Shown.THEMED, frame.shown);
		assertEquals(context + ": one mouse sample per BeforeMenuRender", 1, frame.prepareReads);
		assertEquals(context + ": final draw must not read the mouse", 0, frame.renderReads);
		assertEquals(context + ": one native probe draw", 1, frame.nativeDraws);
		assertDrawnChain(context, frame, model);
	}

	/** Native or themed, never blank, one mouse sample; a themed frame draws native's chain. */
	private static void assertNeverWrong(String context, Frame frame, NativeModel model)
	{
		assertNotEquals(context + ": a consumed frame is never left blank", Shown.BLANK, frame.shown);
		assertEquals(context + ": one mouse sample per BeforeMenuRender", 1, frame.prepareReads);
		assertEquals(context + ": final draw must not read the mouse", 0, frame.renderReads);
		if (frame.shown == Shown.THEMED)
		{
			assertDrawnChain(context, frame, model);
		}
	}

	/** The open child and grandchild are themed; no closed child or grandchild is drawn. */
	private static void assertDrawnChain(String context, Frame frame, NativeModel model)
	{
		Rectangle root = model.root.bounds();
		Rectangle open = model.open == null ? null : model.open.bounds();
		Rectangle grand = model.openGrandchild == null ? null : model.openGrandchild.bounds();
		if (open != null)
		{
			Point inside = interiorPoint(open, root);
			assertTrue(context + ": open child has exclusive pixels", inside != null);
			assertNotEquals(context + ": native open child is themed",
				0, frame.output.getRGB(inside.getX(), inside.getY()) >>> 24);
		}
		if (grand != null)
		{
			Point inside = interiorPoint(grand, root, open);
			if (inside != null)
			{
				assertNotEquals(context + ": native open grandchild is themed",
					0, frame.output.getRGB(inside.getX(), inside.getY()) >>> 24);
			}
		}
		List<NativeMenu> closed = new ArrayList<>(model.children);
		closed.addAll(model.grandchildren);
		for (NativeMenu menu : closed)
		{
			if (menu == model.open || menu == model.openGrandchild || menu.width == 0)
			{
				continue;
			}
			Point inside = interiorPoint(menu.bounds(), root, open, grand);
			if (inside != null)
			{
				assertEquals(context + ": closed menu " + menu.bounds() + " is not drawn",
					0, frame.output.getRGB(inside.getX(), inside.getY()) >>> 24);
			}
		}
	}

	/** A point well inside {@code rect}, clear of every drawn menu (nulls ignored) and theme shadows. */
	private static Point interiorPoint(Rectangle rect, Rectangle... drawn)
	{
		Rectangle canvas = new Rectangle(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
		Rectangle visible = rect.intersection(canvas);
		List<Rectangle> clear = new ArrayList<>();
		for (Rectangle menu : drawn)
		{
			if (menu != null)
			{
				clear.add(grown(menu, 8));
			}
		}
		for (int y = visible.y + 6; y < visible.y + visible.height - 6; y++)
		{
			for (int x = visible.x + 6; x < visible.x + visible.width - 6; x++)
			{
				boolean free = true;
				for (Rectangle menu : clear)
				{
					free &= !menu.contains(x, y);
				}
				if (free)
				{
					return new Point(x, y);
				}
			}
		}
		return null;
	}

	private static Rectangle grown(Rectangle rect, int amount)
	{
		Rectangle copy = new Rectangle(rect);
		copy.grow(amount, amount);
		return copy;
	}

	private static boolean sameImage(BufferedImage first, BufferedImage second)
	{
		for (int y = 0; y < first.getHeight(); y++)
		{
			for (int x = 0; x < first.getWidth(); x++)
			{
				if (first.getRGB(x, y) != second.getRGB(x, y))
				{
					return false;
				}
			}
		}
		return true;
	}

	private static void line(List<Point> path, Point from, Point to, int pixelsPerCall)
	{
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		int steps = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)) / pixelsPerCall);
		for (int i = 1; i <= steps; i++)
		{
			path.add(new Point(from.getX() + dx * i / steps, from.getY() + dy * i / steps));
		}
	}

	private static void dwell(List<Point> path, Point at, int calls)
	{
		for (int i = 0; i < calls; i++)
		{
			path.add(at);
		}
	}

	/**
	 * The long Menu Entry Swapper root from the trace: 76 rows at x=434 with a 212px width and
	 * 71 child menus, plain entries 0-1 (bottom rows) and 73-75 (top rows).
	 */
	private static NativeModel longLoggedRoot(int scroll)
	{
		return new NativeModel(434, 0, 212, 76, true, scroll, entry -> entry >= 2 && entry <= 72);
	}

	private interface ModelFactory
	{
		NativeModel create();
	}

	private enum Shown
	{
		THEMED, NATIVE, BLANK
	}

	private static final class Frame
	{
		private Shown shown;
		private BufferedImage output;
		private int prepareReads;
		private int renderReads;
		private int nativeDraws;
	}

	/** Drives one client frame through the plugin's BeforeMenuRender handler and the overlay. */
	private static final class Harness
	{
		private final NativeModel model;
		private final MenuOverlay overlay;
		private final RightClickStylerPlugin plugin = new RightClickStylerPlugin();
		private Runnable afterPrepare = () -> { };

		private final MutableConfig config = new MutableConfig();
		private final ThemeManager themes;
		/** Fail the frame if a verified cache hit differed from a fresh render. */
		private boolean failOnVerifyMismatch;

		/** @param imageCacheVerify -1 cache off; 0 cache on unverified; n verify every nth hit */
		private Harness(NativeModel model, int imageCacheVerify) throws Exception
		{
			this.model = model;
			themes = new ThemeManager(() -> null, (key, value) -> { }, config, new Gson());
			Constructor<MenuOverlay> constructor = MenuOverlay.class.getDeclaredConstructor(
				Client.class, RightClickStylerConfig.class, ThemeManager.class);
			constructor.setAccessible(true);
			overlay = constructor.newInstance(model.client, config, themes);
			overlay.setImageCache(imageCacheVerify >= 0, Math.max(0, imageCacheVerify));
			failOnVerifyMismatch = imageCacheVerify == 1;
			setField(plugin, "client", model.client);
			setField(plugin, "menuOverlay", overlay);
			setField(plugin, "running", true); // as after startUp
		}

		/** Through the submenu path directly. */
		private Frame frame() throws Exception
		{
			return frame(false);
		}

		/** Through the plugin's whole {@code BeforeMenuRender} subscriber, as RuneLite delivers it. */
		private Frame eventFrame() throws Exception
		{
			return frame(true);
		}

		/** The plugin's real shutDown, as PluginManager runs it on the EDT after unregistering it. */
		private void stopPlugin()
		{
			try
			{
				// Its only constructor is private (Guice-injected); nothing it stores is used by remove.
				Constructor<?> constructor = OverlayManager.class.getDeclaredConstructors()[0];
				constructor.setAccessible(true);
				setField(plugin, "overlayManager",
					constructor.newInstance(new Object[constructor.getParameterCount()]));
				setField(plugin, "localPlayer", new LocalPlayerSnapshot());
			}
			catch (Exception e)
			{
				throw new AssertionError(e);
			}
			plugin.shutDown();
		}

		private Frame frame(boolean viaEvent) throws Exception
		{
			int[] before = model.pixels.clone();
			long blits = (long) field(overlay, "submenuBlits");
			int draws = model.nativeDraws;
			int reads = model.liveReads;
			BeforeMenuRender event = new BeforeMenuRender();
			if (viaEvent)
			{
				plugin.onBeforeMenuRender(event);
			}
			else
			{
				assertTrue("the submenu path owns submenu events", plugin.handleSubmenuFrame(event));
			}
			int preparedReads = model.liveReads;
			assertArrayEquals("the native probe restores the framebuffer", before, model.pixels);
			afterPrepare.run();
			BufferedImage output = new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = output.createGraphics();
			try
			{
				overlay.render(graphics);
			}
			finally
			{
				graphics.dispose();
			}
			Frame frame = new Frame();
			frame.shown = !event.isConsumed() ? Shown.NATIVE
				: (long) field(overlay, "submenuBlits") > blits ? Shown.THEMED : Shown.BLANK;
			frame.output = output;
			frame.prepareReads = preparedReads - reads;
			frame.renderReads = model.liveReads - preparedReads;
			frame.nativeDraws = model.nativeDraws - draws;
			if (failOnVerifyMismatch)
			{
				assertEquals("a reused image always equals a fresh render", 0L,
					field(overlay, "imageVerifyMismatchTotal"));
			}
			return frame;
		}

		private SubmenuImageCache.Miss lastMiss() throws Exception
		{
			return (SubmenuImageCache.Miss) field(overlay, "lastImageMiss");
		}
	}

	/** Config with mutable rendering inputs; everything else keeps its default. */
	private static final class MutableConfig implements RightClickStylerConfig
	{
		private boolean showHitRows;
		private int textOffsetX = RightClickStylerConfig.super.textOffsetX();
		private int throwOnHighlights;

		@Override
		public boolean showHitRows()
		{
			return showHitRows;
		}

		@Override
		public int textOffsetX()
		{
			return textOffsetX;
		}

		@Override
		public boolean highlightsEnabled()
		{
			if (throwOnHighlights > 0)
			{
				throwOnHighlights--;
				throw new IllegalStateException("config read failed");
			}
			return RightClickStylerConfig.super.highlightsEnabled();
		}
	}

	/** Native menu state as the injected client keeps it, with a live AWT mouse beside it. */
	private static final class NativeModel
	{
		private final NativeMenu root;
		private final NativeMenu[] childByEntry;
		private final List<NativeMenu> children = new ArrayList<>();
		private final String[] rootEntryOptions;
		private final int rows;
		private final boolean scrollable;
		private int scroll;
		private int gameCycle = 1000;
		private final int[] pixels = new int[CANVAS_WIDTH * CANVAS_HEIGHT];
		private final Client client;
		private GameState gameState = GameState.LOGGED_IN;
		private Point live;
		private NativeMenu open;
		private boolean hasPending;
		private NativeMenu pending;
		private int pendingCycles;
		private int openChanges;
		private int liveReads;
		private int nativeDraws;
		private Runnable afterNativeDraw = () -> { };
		private Runnable beforeEachClientCall = () -> { };
		private final List<NativeMenu> grandchildren = new ArrayList<>();
		/** Open under {@link #open}; set directly by tests, cleared when the open child changes. */
		private NativeMenu openGrandchild;
		/** Inside a drawn menu but left untouched by the native draw (contradicts A1). */
		private Rectangle unpainted;
		/** Painted by the native draw outside every open menu (contradicts A2). */
		private Rectangle halo;
		/** The mouse sample the native draw highlights rows by: the client's per-cycle copy. */
		private Point drawMouse;
		/** Changes every glyph: a native draw that no longer matches a remembered image. */
		private int glyphSalt;
		/** Draws every menu as one flat colour, with no text: the harness before text emulation. */
		private boolean plainNativeDraw;
		/** When set, the rasterizer and buffer provider return this array instead. */
		private int[] bufferOverride;
		private net.runelite.api.IndexedSprite[] modIcons;

		private NativeModel(int x, int y, int width, int rows, boolean scrollable, int scroll,
			IntPredicate hasChild)
		{
			this.rows = rows;
			this.scrollable = scrollable;
			this.scroll = scroll;
			root = new NativeMenu(width);
			root.setBounds(x, y, width, nativeHeight(rows));
			childByEntry = new NativeMenu[rows];
			rootEntryOptions = new String[rows];
			MenuEntry[] entries = new MenuEntry[rows];
			for (int entry = 0; entry < rows; entry++)
			{
				rootEntryOptions[entry] = "Root " + entry;
				NativeMenu child = null;
				if (hasChild.test(entry))
				{
					child = new NativeMenu(190 + entry * 7 % 60);
					MenuEntry[] childEntries = new MenuEntry[CHILD_ROWS];
					for (int row = 0; row < CHILD_ROWS; row++)
					{
						childEntries[row] = entry(new String[]{"Child " + entry + "." + row}, 0, null);
					}
					child.entries = childEntries;
					childByEntry[entry] = child;
					children.add(child);
				}
				entries[entry] = entry(rootEntryOptions, entry, child);
			}
			root.entries = entries;
			Rasterizer raster = (Rasterizer) Proxy.newProxyInstance(Rasterizer.class.getClassLoader(),
				new Class<?>[]{Rasterizer.class}, (proxy, method, args) -> buffer(method.getName()));
			BufferProvider provider = (BufferProvider) Proxy.newProxyInstance(
				BufferProvider.class.getClassLoader(), new Class<?>[]{BufferProvider.class},
				(proxy, method, args) -> buffer(method.getName()));
			client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
				new Class<?>[]{Client.class}, (proxy, method, args) ->
				{
					beforeEachClientCall.run();
					switch (method.getName())
					{
						case "isMenuOpen": return true;
						case "getGameState": return gameState;
						case "getMenu": return root;
						case "isMenuScrollable": return scrollable;
						case "getMenuScroll": return NativeModel.this.scroll; // the field, not the constructor parameter
						case "getGameCycle": return gameCycle;
						case "getCanvasWidth": return CANVAS_WIDTH;
						case "getCanvasHeight": return CANVAS_HEIGHT;
						case "getMouseCanvasPosition":
							liveReads++;
							return live;
						case "getRasterizer": return raster;
						case "getBufferProvider": return provider;
						case "getLocalPlayer": return null;
						case "getModIcons": return modIcons;
						case "getFPS": return 144;
						case "drawOriginalMenu":
							nativeDraws++;
							nativeDraw();
							afterNativeDraw.run();
							return null;
						default: throw new UnsupportedOperationException(method.getName());
					}
				});
		}

		private Object buffer(String method)
		{
			switch (method)
			{
				case "getPixels": return bufferOverride != null ? bufferOverride : pixels;
				case "getWidth": return CANVAS_WIDTH;
				case "getHeight": return CANVAS_HEIGHT;
				default: throw new UnsupportedOperationException(method);
			}
		}

		private static int nativeHeight(int entries)
		{
			return 22 + entries * MenuRenderer.ROW_HEIGHT;
		}

		private NativeMenu childAtRow(int row)
		{
			return childByEntry[rows - 1 - row];
		}

		/** A previously opened child that native has since closed but not cleared. */
		private void retain(int row, int x, int y, int width)
		{
			childAtRow(row).setBounds(x, y, width, nativeHeight(CHILD_ROWS));
		}

		private void openLogged(int row, int x, int y, int width)
		{
			retain(row, x, y, width);
			open = childAtRow(row);
		}

		/** Gives the child of root {@code row} a grandchild on its child row {@code childRow}. */
		private NativeMenu nest(int row, int childRow, int x, int y, int width, int height)
		{
			NativeMenu child = childAtRow(row);
			NativeMenu grandchild = new NativeMenu(width);
			MenuEntry[] grandEntries = new MenuEntry[CHILD_ROWS];
			for (int i = 0; i < CHILD_ROWS; i++)
			{
				grandEntries[i] = entry(new String[]{"Grandchild " + row + "." + childRow + "." + i}, 0, null);
			}
			grandchild.entries = grandEntries;
			grandchild.setBounds(x, y, width, height);
			child.entries[CHILD_ROWS - 1 - childRow] = entry(new String[]{"Nested " + row + "." + childRow}, 0, grandchild);
			grandchildren.add(grandchild);
			return grandchild;
		}

		private String openBounds()
		{
			return open == null ? "none" : String.valueOf(open.bounds());
		}

		private int retainedCount()
		{
			int count = 0;
			for (NativeMenu child : children)
			{
				count += child != open && child.width > 0 ? 1 : 0;
			}
			return count;
		}

		private int hoveredRow(Point mouse)
		{
			if (mouse == null || mouse.getX() < root.x || mouse.getX() >= root.x + root.width)
			{
				return -1;
			}
			int top = root.y + MenuRenderer.HEADER_HEIGHT;
			if (mouse.getY() < top || mouse.getY() >= Math.min(root.y + root.height, CANVAS_HEIGHT))
			{
				return -1;
			}
			int row = scroll + (mouse.getY() - top) / MenuRenderer.ROW_HEIGHT;
			return row < rows ? row : -1;
		}

		/** The client's per-cycle input step: the only place the open child changes. */
		private void cycle()
		{
			gameCycle++;
			Point snapshot = live;
			drawMouse = snapshot;
			if (open != null && snapshot != null && open.bounds().contains(snapshot.getX(), snapshot.getY()))
			{
				hasPending = false;
				return;
			}
			int row = hoveredRow(snapshot);
			if (row < 0)
			{
				hasPending = false;
				return;
			}
			NativeMenu target = childAtRow(row);
			if (target == open)
			{
				hasPending = false;
				return;
			}
			if (!hasPending || pending != target)
			{
				hasPending = true;
				pending = target;
				pendingCycles = 0;
			}
			if (++pendingCycles >= OPEN_DELAY_CYCLES)
			{
				if (target != null)
				{
					position(target, row);
				}
				open = target;
				openGrandchild = null;
				openChanges++;
				hasPending = false;
			}
		}

		private void position(NativeMenu child, int row)
		{
			int height = nativeHeight(child.entries.length);
			int rowTop = root.y + MenuRenderer.HEADER_HEIGHT + (row - scroll) * MenuRenderer.ROW_HEIGHT;
			int y = Math.max(0, Math.min(rowTop - MenuRenderer.HEADER_HEIGHT, CANVAS_HEIGHT - height));
			int x = root.x + root.width;
			if (x + child.openWidth > CANVAS_WIDTH)
			{
				x = root.x - child.openWidth;
			}
			child.setBounds(x, y, child.openWidth, height);
		}

		/**
		 * Reads native state only; the live mouse cannot change what is open. Each menu is drawn
		 * as the client's menu draw does: a filled rectangle, a black header bar and body outline,
		 * the opener's target as the header, and each row's option, target and submenu suffix in
		 * white, or yellow under the cycle's mouse sample, over a black shadow. Glyphs are a
		 * deterministic pattern per character, standing in for the client's font.
		 */
		private void nativeDraw()
		{
			drawMenu(root, "Choose Option");
			if (open != null)
			{
				drawMenu(open, openerTarget(root, open));
				if (openGrandchild != null)
				{
					drawMenu(openGrandchild, openerTarget(open, openGrandchild));
				}
			}
			if (halo != null)
			{
				fill(halo, GRAY);
			}
		}

		private String openerTarget(NativeMenu parent, NativeMenu child)
		{
			for (MenuEntry entry : parent.entries)
			{
				if (entry != null && entry.getSubMenu() == child)
				{
					return entry.getTarget();
				}
			}
			return "";
		}

		private void drawMenu(NativeMenu menu, String header)
		{
			Rectangle r = menu.bounds();
			if (!plainNativeDraw)
			{
				fill(r, NATIVE_FILL);
				fill(r, new Rectangle(r.x + 1, r.y + 1, r.width - 2, 16), 0xFF000000);
				outline(r, new Rectangle(r.x + 1, r.y + 18, r.width - 2, r.height - 19));
				text(r, header == null ? "" : header, r.x + 3, r.y + 14, NATIVE_FILL, false);
				int count = menu.entries.length;
				int skip = menu == root ? scroll : 0;
				for (int i = 0; i < count; i++)
				{
					MenuEntry entry = menu.entries[i];
					int position = count - 1 - i - skip;
					int baseline = r.y + 31 + 15 * position;
					String target = entry.getTarget();
					String label = entry.getOption() + (target == null || target.isEmpty() ? "" : " " + target)
						+ (entry.getSubMenu() != null ? " ->" : "");
					boolean hovered = drawMouse != null && drawMouse.getX() > r.x && drawMouse.getX() < r.x + r.width
						&& drawMouse.getY() > baseline - 13 && drawMouse.getY() < baseline + 3;
					text(r, label, r.x + 3, baseline, hovered ? 0xFFFFFF00 : 0xFFFFFFFF, true);
				}
			}
			else
			{
				fill(r, GRAY);
			}
		}

		private void fill(Rectangle rect, int colour)
		{
			fill(rect, rect, colour);
		}

		private void fill(Rectangle menu, Rectangle rect, int colour)
		{
			Rectangle visible = rect.intersection(menu).intersection(new Rectangle(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT));
			for (int y = visible.y; y < visible.y + visible.height; y++)
			{
				for (int x = visible.x; x < visible.x + visible.width; x++)
				{
					plot(menu, x, y, colour);
				}
			}
		}

		private void outline(Rectangle menu, Rectangle rect)
		{
			for (int x = rect.x; x < rect.x + rect.width; x++)
			{
				plot(menu, x, rect.y, 0xFF000000);
				plot(menu, x, rect.y + rect.height - 1, 0xFF000000);
			}
			for (int y = rect.y; y < rect.y + rect.height; y++)
			{
				plot(menu, rect.x, y, 0xFF000000);
				plot(menu, rect.x + rect.width - 1, y, 0xFF000000);
			}
		}

		/** Colour tags change the colour, as the client's font does; other characters are glyphs. */
		private void text(Rectangle menu, String text, int x, int baseline, int base, boolean shadow)
		{
			int colour = base;
			for (int pass = shadow ? 0 : 1; pass < 2; pass++)
			{
				int pen = x;
				colour = base;
				for (int i = 0; i < text.length(); i++)
				{
					if (text.startsWith("<col=", i) && text.indexOf('>', i) > 0)
					{
						int end = text.indexOf('>', i);
						colour = 0xFF000000 | Integer.parseInt(text.substring(i + 5, end), 16);
						i = end;
						continue;
					}
					if (text.startsWith("</col>", i))
					{
						colour = base;
						i += 5;
						continue;
					}
					int bits = text.charAt(i) * 0x9E3779B1 ^ glyphSalt;
					for (int gy = 0; gy < 7; gy++)
					{
						for (int gx = 0; gx < 4; gx++)
						{
							if (text.charAt(i) != ' ' && (bits >>> (gy * 4 + gx) & 1) != 0)
							{
								int px = pen + gx + (pass == 0 ? 1 : 0);
								int py = baseline - 9 + gy + (pass == 0 ? 1 : 0);
								plot(menu, px, py, pass == 0 ? 0xFF000000 : colour);
							}
						}
					}
					pen += 5;
				}
			}
		}

		/** Native writes only inside the menu it draws. */
		private void plot(Rectangle menu, int x, int y, int colour)
		{
			if (menu.contains(x, y) && x >= 0 && y >= 0 && x < CANVAS_WIDTH && y < CANVAS_HEIGHT
				&& (unpainted == null || !unpainted.contains(x, y)))
			{
				pixels[y * CANVAS_WIDTH + x] = colour;
			}
		}
	}

	private static final class NativeMenu implements Menu
	{
		private final int openWidth;
		private int x;
		private int y;
		private int width;
		private int height;
		private MenuEntry[] entries = new MenuEntry[0];

		private NativeMenu(int openWidth)
		{
			this.openWidth = openWidth;
		}

		private void setBounds(int x, int y, int width, int height)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		private Rectangle bounds()
		{
			return new Rectangle(x, y, width, height);
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

	/** A read-only entry whose option is read through {@code options[index]}. */
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
				if (method.getName().startsWith("set") || method.getName().startsWith("create")
					|| method.getName().startsWith("delete"))
				{
					throw new UnsupportedOperationException(method.getName());
				}
				Class<?> type = method.getReturnType();
				if (type == boolean.class)
				{
					return false;
				}
				if (type == int.class)
				{
					return 0;
				}
				return null;
			});
	}

	/**
	 * A read-only entry with its own click callback. Any setter, creator, deleter or callback
	 * replacement throws, so a frame that touched a native entry or hitbox fails the test.
	 */
	private static MenuEntry actionEntry(String option, String target, MenuAction action, Menu subMenu,
		java.util.function.Consumer<MenuEntry> callback)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
			{
				String name = method.getName();
				if (name.startsWith("set") || name.startsWith("create") || name.startsWith("delete")
					|| (name.equals("onClick") && args != null && args.length > 0))
				{
					throw new UnsupportedOperationException(name);
				}
				switch (name)
				{
					case "getOption": return option;
					case "getTarget": return target;
					case "getType": return action;
					case "getSubMenu": return subMenu;
					case "getItemId": return -1;
					case "onClick": return callback;
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					case "toString": return option;
					default:
						Class<?> type = method.getReturnType();
						if (type == boolean.class) return false;
						if (type == int.class) return 0;
						return null;
				}
			});
	}

	private static MenuEntry entry(String option, String target, MenuAction action, Menu subMenu)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getOption": return option;
					case "getTarget": return target;
					case "getType": return action;
					case "getSubMenu": return subMenu;
					case "getItemId": return -1;
					case "hashCode": return System.identityHashCode(proxy);
					case "equals": return proxy == args[0];
					default:
						Class<?> type = method.getReturnType();
						if (type == boolean.class) return false;
						if (type == int.class) return 0;
						return null;
				}
			});
	}

	private static Object field(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
