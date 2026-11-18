package com.rightclickstyler;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The authoritative gate on real probe results: only one uniquely RESOLVED, well-formed chain
 * on a verified framebuffer passes, and nothing unproven is ever selected.
 */
public class ExactAuthorityGateTest
{
	private static final ExactCoverageResolverTest.Paint OPAQUE = ExactCoverageResolverTest.Paint.OPAQUE;

	private static SubmenuPrototypeProbe.Result probe(ExactCoverageResolverTest.Fixture f,
		ExactCoverageResolverTest.Paint paint, net.runelite.api.Menu... chain)
	{
		int[] pixels = new int[ExactCoverageResolverTest.WIDTH * ExactCoverageResolverTest.HEIGHT];
		SubmenuPrototypeProbe probe = new SubmenuPrototypeProbe();
		probe.requireExact(true); // authoritative mode evaluates it whatever the shadow property says
		return probe.samplePixels(pixels, ExactCoverageResolverTest.WIDTH, ExactCoverageResolverTest.HEIGHT,
			SubmenuTree.capture(f.root), () -> paint.paint(pixels, f.root, chain));
	}

	@Test
	public void aUniquelyResolvedChainPassesAndIsTheExactChain()
	{
		ExactCoverageResolverTest.Fixture f = ExactCoverageResolverTest.loggedFrame587();
		SubmenuPrototypeProbe.Result result = probe(f, OPAQUE, f.child(2));
		assertEquals("the previous resolver cannot tell", SubmenuPrototypeProbe.State.AMBIGUOUS, result.state);
		assertTrue(result.framebufferVerified);
		assertEquals(MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED, MenuOverlay.classifyExact(result));
		assertNull(MenuOverlay.authoritativeRejection(result, SubmenuTree.capture(f.root)));
		assertArrayEquals(new int[]{2}, result.exact.chain);
	}

	@Test
	public void rootOnlyResolutionPassesWithAnEmptyChain()
	{
		ExactCoverageResolverTest.Fixture f = ExactCoverageResolverTest.loggedFrame587();
		SubmenuPrototypeProbe.Result result = probe(f, OPAQUE);
		assertEquals(MenuOverlay.ExactComparison.AGREE, MenuOverlay.classifyExact(result));
		assertNull(MenuOverlay.authoritativeRejection(result, SubmenuTree.capture(f.root)));
		assertEquals(0, result.exact.chain.length);
	}

	@Test
	public void everyUnresolvedReadingStaysNative()
	{
		ExactCoverageResolverTest.Fixture identical = new ExactCoverageResolverTest.Fixture(100, 4);
		identical.child(0).setBounds(328, 300, 220, 97);
		identical.child(1).setBounds(328, 300, 220, 97);
		assertRejected(identical, probe(identical, OPAQUE, identical.child(1)),
			ExactCoverageResolver.State.AMBIGUOUS, MenuOverlay.PrototypeOutcome.FALLBACK_AMBIGUOUS);

		ExactCoverageResolverTest.Fixture underRoot = new ExactCoverageResolverTest.Fixture(100, 20);
		underRoot.child(0).setBounds(120, 100, 150, 97);
		assertRejected(underRoot, probe(underRoot, OPAQUE),
			ExactCoverageResolver.State.UNEVIDENCED, MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED);

		ExactCoverageResolverTest.Fixture hole = ExactCoverageResolverTest.loggedFrame587();
		assertRejected(hole, probe(hole, ExactCoverageResolverTest.Paint.WITH_HOLE, hole.child(2)),
			ExactCoverageResolver.State.UNEXPLAINED, MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED);

		ExactCoverageResolverTest.Fixture halo = ExactCoverageResolverTest.loggedFrame587();
		SubmenuPrototypeProbe.Result haloResult = probe(halo, ExactCoverageResolverTest.Paint.WITH_HALO, halo.child(2));
		assertNotEquals(ExactCoverageResolver.State.RESOLVED, haloResult.exact.state);
		assertTrue(MenuOverlay.authoritativeRejection(haloResult, SubmenuTree.capture(halo.root)) != null);
	}

	private static void assertRejected(ExactCoverageResolverTest.Fixture f, SubmenuPrototypeProbe.Result result,
		ExactCoverageResolver.State state, MenuOverlay.PrototypeOutcome outcome)
	{
		assertTrue("the evidence itself is sound", result.framebufferVerified);
		assertEquals(result.exact.toString(), state, result.exact.state);
		assertEquals(0, result.exact.chain.length);
		assertEquals(result.exact.toString(), outcome, MenuOverlay.authoritativeRejection(result, SubmenuTree.capture(f.root)));
	}

	@Test
	public void errorsAndMissingOrMalformedReadingsStayNative()
	{
		ExactCoverageResolverTest.Fixture f = new ExactCoverageResolverTest.Fixture(100, 4);
		f.child(0).setBounds(328, 100, 220, 97);
		f.child(1).setBounds(328, 300, 220, 97);
		SubmenuTree tree = SubmenuTree.capture(f.root);
		SubmenuPrototypeProbe.Result result = probe(f, OPAQUE, f.child(1));
		assertEquals(SubmenuPrototypeProbe.State.ACTIVE, result.state);
		assertNull(MenuOverlay.authoritativeRejection(result, tree));
		int changed = result.exact.changedPixels;

		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.ERROR, new int[0], 0, new int[0], 0);
		assertEquals(MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED, MenuOverlay.authoritativeRejection(result, tree));
		assertEquals("an error never counts as agreement", MenuOverlay.ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED,
			MenuOverlay.classifyExact(result));

		result.exact = ExactCoverageResolver.Outcome.NOT_EVALUATED;
		assertEquals(MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED, MenuOverlay.authoritativeRejection(result, tree));

		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.RESOLVED, new int[]{1}, 2, new int[]{1}, changed);
		assertEquals("resolved requires exactly one explaining chain",
			MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED, MenuOverlay.authoritativeRejection(result, tree));

		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.RESOLVED, new int[]{7}, 1, new int[]{7}, changed);
		assertEquals("a chain member outside the tree",
			MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED, MenuOverlay.authoritativeRejection(result, tree));

		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.RESOLVED, new int[]{0, 1}, 1, new int[]{0, 1}, changed);
		assertEquals("siblings are not a chain",
			MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED, MenuOverlay.authoritativeRejection(result, tree));
	}

	@Test
	public void nestedChainMustBeParentFirst()
	{
		ExactCoverageResolverTest.NestedFixture f = new ExactCoverageResolverTest.NestedFixture();
		f.parent.setBounds(328, 200, 220, 97);
		f.drawnGrandchild.setBounds(548, 215, 180, 97);
		f.staleGrandchild.setBounds(548, 215, 176, 97);
		SubmenuTree tree = SubmenuTree.capture(f.root);
		SubmenuPrototypeProbe.Result result = probe(f, OPAQUE, f.parent, f.drawnGrandchild);
		int parent = f.index(f.parent);
		int grandchild = f.index(f.drawnGrandchild);
		assertArrayEquals(new int[]{parent, grandchild}, result.exact.chain);
		assertNull(MenuOverlay.authoritativeRejection(result, tree));
		result.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.RESOLVED,
			new int[]{grandchild}, 1, new int[]{grandchild}, result.exact.changedPixels);
		assertEquals("a grandchild without its parent", MenuOverlay.PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED,
			MenuOverlay.authoritativeRejection(result, tree));
	}

	@Test
	public void framebufferFailuresKeepTheirOutcomesEvenWithAResolvedReading()
	{
		ExactCoverageResolverTest.Fixture f = ExactCoverageResolverTest.loggedFrame587();
		SubmenuTree tree = SubmenuTree.capture(f.root);
		int[] pixels = new int[ExactCoverageResolverTest.WIDTH * ExactCoverageResolverTest.HEIGHT];
		SubmenuPrototypeProbe probe = new SubmenuPrototypeProbe();
		probe.requireExact(true);

		// The native draw moves the drawn child: the evidence no longer describes the frame.
		SubmenuPrototypeProbe.Result moved = probe.samplePixels(pixels, ExactCoverageResolverTest.WIDTH,
			ExactCoverageResolverTest.HEIGHT, tree, () ->
			{
				OPAQUE.paint(pixels, f.root, f.child(2));
				f.child(2).setBounds(649, 496, 232, 97);
			});
		assertEquals(SubmenuPrototypeProbe.State.GEOMETRY_CHANGED, moved.state);
		assertEquals("read before the restore", ExactCoverageResolver.State.RESOLVED, moved.exact.state);
		assertFalse(moved.framebufferVerified);
		assertEquals(MenuOverlay.ExactComparison.OLD_FAILED, MenuOverlay.classifyExact(moved));
		assertEquals(MenuOverlay.PrototypeOutcome.FALLBACK_GEOMETRY_CHANGED, MenuOverlay.authoritativeRejection(moved, tree));

		// The native draw never reaches the buffer.
		ExactCoverageResolverTest.Fixture g = ExactCoverageResolverTest.loggedFrame587();
		SubmenuTree gTree = SubmenuTree.capture(g.root);
		SubmenuPrototypeProbe.Result missed = probe.samplePixels(pixels, ExactCoverageResolverTest.WIDTH,
			ExactCoverageResolverTest.HEIGHT, gTree, () -> { });
		assertFalse(missed.framebufferVerified);
		assertEquals(ExactCoverageResolver.State.NOT_EVALUATED, missed.exact.state);
		assertEquals(MenuOverlay.PrototypeOutcome.FALLBACK_NATIVE_DRAW_MISSED, MenuOverlay.authoritativeRejection(missed, gTree));
		assertArrayEquals("every probe restores the framebuffer", new int[pixels.length], pixels);
	}
}
