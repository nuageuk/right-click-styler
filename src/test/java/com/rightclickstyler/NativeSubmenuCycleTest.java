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
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Rasterizer;
import net.runelite.api.events.BeforeMenuRender;
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
 * the previous resolver authoritative (the default) and with exact coverage authoritative.
 */
@RunWith(Parameterized.class)
public class NativeSubmenuCycleTest
{
	@Parameters(name = "exactAuthoritative={0}")
	public static Collection<Object[]> modes()
	{
		return Arrays.asList(new Object[]{false}, new Object[]{true});
	}

	private final boolean authoritative;

	public NativeSubmenuCycleTest(boolean authoritative)
	{
		this.authoritative = authoritative;
	}

	private Harness harness(NativeModel model) throws Exception
	{
		return new Harness(model, authoritative);
	}

	private static final int CANVAS_WIDTH = 990;
	private static final int CANVAS_HEIGHT = 592;
	private static final int OPEN_DELAY_CYCLES = 2;
	private static final int CHILD_ROWS = 5;
	private static final int GRAY = 0xFF808080;

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
		assertEquals(0L, field(harness.overlay, "prototypeChangedDrops"));
		assertEquals(0L, field(harness.overlay, "prototypeClosedDrops"));
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
	public void shadowReuseNeverMismatchesWhileTheMouseRacesAcrossCycles() throws Exception
	{
		assumeTrue("shadow validation disabled", MenuOverlay.submenuShadowEnabled());
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
		int kinds = SubmenuChainShadow.Kind.values().length;
		int verdicts = SubmenuChainShadow.Verdict.values().length;
		int[][] counts = new int[kinds][verdicts];
		int frames = 0;
		while (step[0] < path.size() - 1)
		{
			if (frames % 2 == 0)
			{
				model.cycle();
			}
			Frame frame = harness.frame();
			assertThemedNativeState("frame " + frames, frame, model); // rendering ignores the shadow
			SubmenuChainShadow shadow = shadowOf(harness);
			counts[shadow.lastDecision().kind.ordinal()][shadow.lastVerdict().ordinal()]++;
			frames++;
		}
		int same = SubmenuChainShadow.Kind.REUSE_SAME_CYCLE.ordinal();
		int settled = SubmenuChainShadow.Kind.REUSE_SETTLED.ordinal();
		int match = SubmenuChainShadow.Verdict.MATCH.ordinal();
		int mismatch = SubmenuChainShadow.Verdict.MISMATCH.ordinal();
		int unverified = SubmenuChainShadow.Verdict.UNVERIFIED.ordinal();
		String summary = java.util.Arrays.deepToString(counts);
		assertEquals(summary, 0, counts[same][mismatch] + counts[settled][mismatch]);
		assertEquals(summary, 0, counts[same][unverified] + counts[settled][unverified]);
		assertTrue("same-cycle reuse is exercised: " + summary, counts[same][match] > 10);
		assertTrue("settled reuse is exercised: " + summary, counts[settled][match] > 10);
	}

	@Test
	public void delayedOpenUnderAParkedMouseIsNeverReused() throws Exception
	{
		assumeTrue("shadow validation disabled", MenuOverlay.submenuShadowEnabled());
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 290); // row 18: native opens its child after the delay
		Harness harness = harness(model);
		NativeMenu rowEighteen = model.childAtRow(18);
		for (int i = 0; i < 12; i++)
		{
			model.cycle();
			Frame frame = harness.frame();
			assertThemedNativeState("cycle " + i, frame, model);
			SubmenuChainShadow.Decision decision = shadowOf(harness).lastDecision();
			if (model.open != rowEighteen)
			{
				assertEquals("cycle " + i + " " + decision, SubmenuChainShadow.Kind.PROBE, decision.kind);
				assertTrue("cycle " + i + " " + decision,
					decision.reason == SubmenuChainShadow.ProbeReason.UNSETTLED
						|| decision.reason == SubmenuChainShadow.ProbeReason.NO_PROOF);
			}
			assertFalse("cycle " + i, shadowOf(harness).lastVerdict() == SubmenuChainShadow.Verdict.MISMATCH);
		}
		assertTrue("native opened row 18 while the mouse stayed still", model.open == rowEighteen);
	}

	@Test
	public void nativeChangeWithinOneCycleIsReportedAsMismatch() throws Exception
	{
		assumeTrue("shadow validation disabled", MenuOverlay.submenuShadowEnabled());
		// Breaks hypothesis H1 on purpose: the shadow must catch it, rendering must not care.
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("proof", harness.frame(), model);
		model.open = model.childAtRow(12); // no cycle, no geometry change
		Frame frame = harness.frame();
		SubmenuChainShadow shadow = shadowOf(harness);
		assertEquals(SubmenuChainShadow.Kind.REUSE_SAME_CYCLE, shadow.lastDecision().kind);
		assertEquals(SubmenuChainShadow.Verdict.MISMATCH, shadow.lastVerdict());
		assertThemedNativeState("rendering follows the probe, not the shadow", frame, model);
		assertEquals(1L, ((long[][]) field(harness.overlay, "shadowVerdicts"))
			[SubmenuChainShadow.Kind.REUSE_SAME_CYCLE.ordinal()][SubmenuChainShadow.Verdict.MISMATCH.ordinal()]);
	}

	@Test
	public void ambiguousProbeUnderReuseIsUnverifiedAndStaysNative() throws Exception
	{
		assumeTrue("shadow validation disabled", MenuOverlay.submenuShadowEnabled());
		NativeModel model = longLoggedRoot(0);
		model.retain(20, 646, 400, 220);
		model.retain(21, 646, 400, 220); // identical rectangles: indistinguishable by pixels
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("proof", harness.frame(), model);
		model.open = model.childAtRow(20);
		Frame frame = harness.frame();
		SubmenuChainShadow shadow = shadowOf(harness);
		assertEquals(SubmenuChainShadow.Kind.REUSE_SAME_CYCLE, shadow.lastDecision().kind);
		assertEquals("an ambiguous probe is not proof of correctness",
			SubmenuChainShadow.Verdict.UNVERIFIED, shadow.lastVerdict());
		assertEquals("the reused chain is never rendered", Shown.NATIVE, frame.shown);
	}

	@Test
	public void menuOpeningAndUnpreparedEventsEndTheProof() throws Exception
	{
		assumeTrue("shadow validation disabled", MenuOverlay.submenuShadowEnabled());
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		harness.frame();
		assertTrue(shadowOf(harness).hasProof());
		harness.plugin.onMenuOpened(null); // RuneLite reuses the same root object
		assertFalse(shadowOf(harness).hasProof());
		harness.frame();
		assertEquals(SubmenuChainShadow.ProbeReason.NO_PROOF, shadowOf(harness).lastDecision().reason);
		assertTrue(shadowOf(harness).hasProof());
		BeforeMenuRender consumed = new BeforeMenuRender();
		consumed.consume();
		harness.plugin.handleSubmenuPrototype(consumed);
		assertFalse("a frame another subscriber consumed proves nothing", shadowOf(harness).hasProof());
		harness.frame();
		model.scroll = 1;
		harness.frame();
		assertEquals(String.valueOf(shadowOf(harness).lastDecision()), SubmenuChainShadow.ProbeReason.SCROLL, shadowOf(harness).lastDecision().reason);
		harness.frame();
		model.rootEntryOptions[40] = "Changed";
		harness.frame();
		assertEquals(SubmenuChainShadow.ProbeReason.STRUCTURE, shadowOf(harness).lastDecision().reason);
	}

	@Test
	public void exactCoverageComparisonIsShadowOnly() throws Exception
	{
		assumeTrue("exact-coverage shadow disabled", SubmenuPrototypeProbe.exactCoverageShadowEnabled());
		assumeTrue("previous resolver authoritative", !authoritative);
		NativeModel model = longLoggedRoot(0);
		model.retain(12, 646, 180, 196);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 250);
		Harness harness = harness(model);
		assertThemedNativeState("exclusive evidence", harness.frame(), model);
		assertEquals(MenuOverlay.ExactComparison.AGREE, field(harness.overlay, "lastExactComparison"));

		// Logged frame 587's near-tie widths at one position: the probe's own resolver is
		// ambiguous, the exact reading picks the drawn width, and the frame still stays native.
		NativeModel tie = longLoggedRoot(0);
		tie.retain(20, 646, 400, 235);
		tie.retain(22, 646, 400, 231);
		tie.openLogged(21, 646, 400, 232);
		tie.live = new Point(549, 340);
		Harness tieHarness = harness(tie);
		Frame frame = tieHarness.frame();
		assertEquals("the exact reading never reaches rendering", Shown.NATIVE, frame.shown);
		assertEquals(MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED, field(tieHarness.overlay, "lastExactComparison"));
		long[] comparisons = (long[]) field(tieHarness.overlay, "exactComparisons");
		assertEquals("unverified, never counted as agreement", 0L, comparisons[MenuOverlay.ExactComparison.AGREE.ordinal()]);
		assertEquals(1L, comparisons[MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED.ordinal()]);
	}

	// ---- Exact coverage as the authoritative resolver (each test runs in both modes) ----

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
	public void nearTieSiblingsAreThemedOnlyWhenExactCoverageIsAuthoritative() throws Exception
	{
		Harness harness = harness(nearTieModel());
		Frame frame = harness.frame();
		assertEquals(MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED, comparison(harness));
		if (authoritative)
		{
			assertThemedNativeState("promoted near tie", frame, harness.model);
			assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.READY_WITH_CHILD));
			assertEquals(1L, field(harness.overlay, "prototypeBlits"));
		}
		else
		{
			assertEquals("the previous resolver is ambiguous and stays native", Shown.NATIVE, frame.shown);
			assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_AMBIGUOUS));
		}

		// Retained 63px below its parent row: the existing detached-child check applies, and the
		// exact reading is the proof that native drew the child at that position.
		Harness detached = harness(nearTieModel(400));
		Frame detachedFrame = detached.frame();
		if (authoritative)
		{
			assertThemedNativeState("detached near tie", detachedFrame, detached.model);
			assertEquals(1L, outcome(detached, MenuOverlay.PrototypeOutcome.READY_WITH_NATIVE_DETACHED_CHILD));
		}
		else
		{
			assertEquals(Shown.NATIVE, detachedFrame.shown);
		}
	}

	@Test
	public void everyPromotedResolutionRecordsCompactEvidence() throws Exception
	{
		Harness harness = harness(nearTieModel());
		harness.frame();
		BoundedEvidenceLog evidence = (BoundedEvidenceLog) field(harness.overlay, "exactEvidence");
		List<String> records = new ArrayList<>();
		evidence.drain(records::add);
		if (!authoritative && !Boolean.getBoolean("rightclickstyler.submenuTrace"))
		{
			assertEquals("shadow mode without the trace keeps its sampled detail log", 0, records.size());
			return;
		}
		assertEquals(1, records.size());
		String record = records.get(0);
		assertTrue(record, record.contains("comparison=OLD_AMBIGUOUS_NEW_RESOLVED"));
		assertTrue(record, record.contains("exact=RESOLVED"));
		assertTrue(record, record.contains("explaining=1"));
		assertTrue("the selected chain with its bounds: " + record, record.matches(".*exactChain=\\[\\d+=646,300,232x97\\].*"));
		assertTrue("the competing covered candidates: " + record, record.matches(".*covered=\\[\\d+=646,300,231x97 \\d+=646,300,232x97\\].*"));
		assertTrue(record, record.contains("probe=AMBIGUOUS"));
		assertTrue(record, record.contains(authoritative
			? "outcome=READY_WITH_CHILD rendered=[" : "outcome=FALLBACK_AMBIGUOUS rendered=none"));
	}

	@Test
	public void evidenceIsStreamedOnEveryTickAndNeverSilentlyLost() throws Exception
	{
		assumeTrue("the evidence stream is on in authoritative mode", authoritative);
		Harness harness = harness(nearTieModel());
		for (int tick = 0; tick < 5; tick++)
		{
			for (int frame = 0; frame < 7; frame++)
			{
				harness.frame();
			}
			harness.overlay.reportPrototype(); // one game tick, inside the 5s summary window
			assertEquals("tick " + tick, 7L * (tick + 1), field(harness.overlay, "exactEvidenceEmitted"));
		}
		assertEquals(35L, field(harness.overlay, "exactEvidenceRecorded"));
		assertEquals(0L, field(harness.overlay, "exactEvidenceDropped"));

		// Without ticks the queue fills; the overflow is counted and reported, not silent.
		// (A small queue keeps the shared test log short.)
		BoundedEvidenceLog small = new BoundedEvidenceLog(4);
		setField(harness.overlay, "exactEvidence", small);
		for (int frame = 0; frame < 7; frame++)
		{
			harness.frame();
		}
		assertEquals(4, small.size());
		harness.overlay.reportPrototype();
		assertEquals(3L, field(harness.overlay, "exactEvidenceDropped"));
		assertEquals(35L + 4, field(harness.overlay, "exactEvidenceEmitted"));
		assertEquals(0, small.size());
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
		assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_AMBIGUOUS));
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
			if (authoritative)
			{
				assertThemedNativeState(context, frame, model);
			}
			else
			{
				assertNeverWrong(context, frame, model);
			}
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
			if (authoritative)
			{
				assertThemedNativeState(context, frame, model);
			}
			else
			{
				assertNeverWrong(context, frame, model);
			}
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
		if (authoritative)
		{
			assertEquals("contained: drawn or not, the pixels are identical", Shown.NATIVE, frame.shown);
			assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_AMBIGUOUS));
		}
		else
		{
			assertNeverWrong("contained grandchild", frame, model);
		}
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
		if (authoritative)
		{
			assertThemedNativeState("visible clipped difference", visibleFrame, visible);
		}
		else
		{
			assertNeverWrong("visible clipped difference", visibleFrame, visible);
		}
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
		if (authoritative)
		{
			assertEquals("evidence against full coverage stays native", Shown.NATIVE, frame.shown);
			assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED));
		}
		else
		{
			assertThemedNativeState("the previous resolver is unchanged", frame, model);
		}
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
		if (authoritative)
		{
			assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED));
		}
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
		assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_GEOMETRY_CHANGED));
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
		assertEquals(1L, outcome(harness, MenuOverlay.PrototypeOutcome.FALLBACK_BUFFER_MISMATCH));
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
		SubmenuPrototypeProbe.Result result = f.probe(ExactCoverageResolverTest.Paint.OPAQUE, f.child(1));
		assertArrayEquals(new int[]{1}, result.activeChain);
		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.RESOLVED,
			new int[]{0}, 1, new int[]{0}, result.exact.changedPixels);
		SubmenuTree tree = SubmenuTree.capture(f.root);
		assertEquals(MenuOverlay.ExactComparison.CONTRADICTORY, MenuOverlay.classifyExact(result));
		assertEquals(MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_CONTRADICTION,
			MenuOverlay.authoritativeRejection(result, tree));

		Harness harness = harness(longLoggedRoot(0));
		java.lang.reflect.Method record = MenuOverlay.class.getDeclaredMethod("exactRecord",
			SubmenuPrototypeProbe.Result.class, SubmenuTree.class, MenuOverlay.ExactComparison.class, boolean.class);
		record.setAccessible(true);
		for (int i = 0; i < 25; i++)
		{
			record.invoke(harness.overlay, result, tree, MenuOverlay.ExactComparison.CONTRADICTORY, authoritative);
		}
		assertEquals(25L, ((long[]) field(harness.overlay, "exactComparisons"))
			[MenuOverlay.ExactComparison.CONTRADICTORY.ordinal()]);
		assertEquals("logged prominently up to the window limit", 25L, field(harness.overlay, "exactContradictionLogs"));
		assertEquals("then counted, never silent", 5L, field(harness.overlay, "exactContradictionSuppressed"));
		assertFalse((boolean) field(harness.overlay, "exactDiagnosticsFailed"));
	}

	@Test
	public void exactDiagnosticFailureNeverInterruptsRendering() throws Exception
	{
		Harness harness = harness(nearTieModel());
		setField(harness.overlay, "exactComparisons", new long[0]); // every record now throws
		for (int i = 0; i < 3; i++)
		{
			Frame frame = harness.frame();
			if (authoritative)
			{
				assertThemedNativeState("frame " + i, frame, harness.model);
			}
			else
			{
				assertEquals(Shown.NATIVE, frame.shown);
			}
			assertEquals("the decision does not depend on bookkeeping",
				MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED, comparison(harness));
		}
		assertTrue((boolean) field(harness.overlay, "exactDiagnosticsFailed"));
		harness.overlay.reportPrototype();
	}

	/** Scrolls the long root up and down under a parked pointer, one row every three frames. */
	private ScrollRun longScroll(boolean authority, int sweeps) throws Exception
	{
		NativeModel model = longLoggedRoot(0);
		model.live = new Point(549, 330); // root row 20 + scroll: native keeps opening there
		Harness harness = new Harness(model, authority);
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
				String context = (authority ? "authoritative" : "shadow") + " frame " + run.frames
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
					MenuOverlay.PrototypeOutcome outcome =
						(MenuOverlay.PrototypeOutcome) field(harness.overlay, "lastOutcome");
					run.fallbackOutcomes.merge(outcome.name(), 1, Integer::sum);
					if (authority)
					{
						assertTrue(context + ": native only when exact coverage did not resolve: "
							+ outcome + " " + comparison,
							outcome == MenuOverlay.PrototypeOutcome.FALLBACK_AMBIGUOUS
								|| outcome == MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED);
					}
				}
				if (++run.frames % 10 == 0)
				{
					harness.overlay.reportPrototype(); // a game tick roughly every 10 frames
				}
			}
		}
		harness.overlay.reportPrototype();
		run.openChanges = model.openChanges;
		run.retained = model.retainedCount();
		run.evidenceRecorded = (long) field(harness.overlay, "exactEvidenceRecorded");
		run.evidenceEmitted = (long) field(harness.overlay, "exactEvidenceEmitted");
		run.evidenceDropped = (long) field(harness.overlay, "exactEvidenceDropped");
		return run;
	}

	private static final class ScrollRun
	{
		private int frames;
		private int themed;
		private int fallbacks;
		private int openChanges;
		private int retained;
		private long evidenceRecorded;
		private long evidenceEmitted;
		private long evidenceDropped;
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
				+ " evidence=" + evidenceRecorded + "/" + evidenceEmitted + "/" + evidenceDropped + " comparisons:");
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
		ScrollRun run = longScroll(authoritative, 3);
		System.out.println("LONGSCROLL authoritative=" + authoritative + " " + run);
		assertTrue("prolonged scrolling: " + run, run.frames > 600);
		assertTrue("native keeps switching children: " + run, run.openChanges > 100);
		assertTrue("many stale rectangles accumulate: " + run, run.retained > 30);
		assertEquals("never contradictory: " + run, 0, run.count(MenuOverlay.ExactComparison.CONTRADICTORY));
		assertEquals(run.toString(), 0L, run.evidenceDropped);
		assertEquals("every recorded frame was emitted: " + run, run.evidenceRecorded, run.evidenceEmitted);
		if (authoritative)
		{
			assertEquals("one record per promoted or withdrawn resolution: " + run,
				run.count(MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED)
					+ run.count(MenuOverlay.ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED), run.evidenceRecorded);
			assertEquals("every frame native fell back on was one exact coverage left unresolved: " + run,
				run.count(MenuOverlay.ExactComparison.BOTH_UNRESOLVED)
					+ run.count(MenuOverlay.ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED), run.fallbacks);
		}
	}

	@Test
	public void authoritativeScrollingFallsBackLessThanThePreviousResolver() throws Exception
	{
		assumeTrue("compares both modes once", authoritative);
		ScrollRun shadow = longScroll(false, 1);
		ScrollRun exact = longScroll(true, 1);
		System.out.println("LONGSCROLL compare shadow: " + shadow);
		System.out.println("LONGSCROLL compare exact:  " + exact);
		assertEquals("identical native sessions", shadow.frames, exact.frames);
		assertEquals(shadow.openChanges, exact.openChanges);
		assertArrayEquals("the same frames classified the same way", shadow.comparisons, exact.comparisons);
		assertTrue(exact + " vs " + shadow, exact.themed > shadow.themed);
		assertEquals("exact gains exactly its promoted frames and loses exactly its withdrawn ones",
			shadow.themed + exact.count(MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED)
				- exact.count(MenuOverlay.ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED), exact.themed);
	}

	private static MenuOverlay.ExactComparison comparison(Harness harness) throws Exception
	{
		return (MenuOverlay.ExactComparison) field(harness.overlay, "lastExactComparison");
	}

	private static long outcome(Harness harness, MenuOverlay.PrototypeOutcome outcome) throws Exception
	{
		return ((long[]) field(harness.overlay, "prototypeOutcomes"))[outcome.ordinal()];
	}

	private static SubmenuChainShadow shadowOf(Harness harness) throws Exception
	{
		return (SubmenuChainShadow) field(harness.overlay, "shadow");
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
			assertEquals(context, 1L, field(harness.overlay, "prototypeBlits"));
			assertEquals(context, 0L, field(harness.overlay, "prototypeChangedDrops"));
			assertTraceComplete(context, harness.overlay);
		}
	}

	@Test
	public void structuralChangeAfterConsumptionIsStillDropped() throws Exception
	{
		if (!MenuOverlay.hardenedLifecycle())
		{
			return; // The baseline A/B lifecycle performs no final validation.
		}
		NativeModel model = longLoggedRoot(0);
		model.openLogged(15, 646, 225, 235);
		model.live = new Point(549, 287);
		Harness harness = harness(model);
		harness.afterPrepare = () -> model.rootEntryOptions[40] = "Changed";
		Frame frame = harness.frame();
		assertEquals("a consumed frame cannot fall back to native", Shown.BLANK, frame.shown);
		assertEquals(1L, field(harness.overlay, "prototypeChangedDrops"));
		assertEquals(0, frame.renderReads);
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
		assertTraceComplete(name, racedHarness.overlay);

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

	private static void assertTraceComplete(String context, MenuOverlay overlay) throws Exception
	{
		if (!Boolean.getBoolean("rightclickstyler.submenuTrace"))
		{
			return;
		}
		Object trace = field(overlay, "currentFrameTrace");
		assertTrue(context, (boolean) field(trace, "consumed"));
		assertTrue(context, (boolean) field(trace, "blitted"));
		assertTrue(context, (boolean) field(trace, "finalized"));
		assertEquals(context, 1, field(trace, "nativeDraws"));
		assertEquals(context, null, field(trace, "finalReason"));
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

		private Harness(NativeModel model, boolean authoritative) throws Exception
		{
			this.model = model;
			RightClickStylerConfig config = new RightClickStylerConfig() { };
			ThemeManager themes = new ThemeManager(() -> null, (key, value) -> { }, config, new Gson());
			Constructor<MenuOverlay> constructor = MenuOverlay.class.getDeclaredConstructor(
				Client.class, RightClickStylerConfig.class, ThemeManager.class);
			constructor.setAccessible(true);
			overlay = constructor.newInstance(model.client, config, themes);
			overlay.setExactCoverageAuthoritative(authoritative);
			setField(plugin, "client", model.client);
			setField(plugin, "menuOverlay", overlay);
		}

		private Frame frame() throws Exception
		{
			int[] before = model.pixels.clone();
			long blits = (long) field(overlay, "prototypeBlits");
			int draws = model.nativeDraws;
			int reads = model.liveReads;
			BeforeMenuRender event = new BeforeMenuRender();
			assertTrue("prototype owns submenu events", plugin.handleSubmenuPrototype(event));
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
				: (long) field(overlay, "prototypeBlits") > blits ? Shown.THEMED : Shown.BLANK;
			frame.output = output;
			frame.prepareReads = preparedReads - reads;
			frame.renderReads = model.liveReads - preparedReads;
			frame.nativeDraws = model.nativeDraws - draws;
			return frame;
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
		/** When set, the rasterizer and buffer provider return this array instead. */
		private int[] bufferOverride;

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
						case "getModIcons": return null;
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

		/** Reads native state only; the live mouse cannot change what is open. */
		private void nativeDraw()
		{
			fill(root.bounds());
			if (open != null)
			{
				fill(open.bounds());
				if (openGrandchild != null)
				{
					fill(openGrandchild.bounds());
				}
			}
			if (halo != null)
			{
				fill(halo);
			}
		}

		private void fill(Rectangle rect)
		{
			Rectangle visible = rect.intersection(new Rectangle(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT));
			for (int y = visible.y; y < visible.y + visible.height; y++)
			{
				for (int x = visible.x; x < visible.x + visible.width; x++)
				{
					if (unpainted == null || !unpainted.contains(x, y))
					{
						pixels[y * CANVAS_WIDTH + x] = GRAY;
					}
				}
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
