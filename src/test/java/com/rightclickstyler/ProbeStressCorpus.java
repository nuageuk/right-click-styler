package com.rightclickstyler;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.api.MenuEntry;

/**
 * Hand-built edge cases for the probe, beyond the seeded corpus of {@link ProbeSemanticsGoldenTest}:
 * identical and near-identical stacked siblings, clipped and offscreen children, root/submenu
 * intersections (overlap, containment both ways), the footprint complexity limit and the
 * candidate area limit, scrolled roots with skipped rows, detached children and grandchildren,
 * roots clipped at every canvas edge (including the negative-width intersections the margin
 * still reaches), 1-5px children (inset clamping), deep chains, and native draws that throw.
 *
 * <p>A case that throws records the exception, then probes a normal follow-up frame on the same
 * instance, so state left behind by an aborted pass is visible in the fingerprint.
 */
final class ProbeStressCorpus
{
	private ProbeStressCorpus()
	{
	}

	interface Case
	{
		String fingerprint(NativeSubmenuProbe probe);
	}

	private static final class Spec
	{
		final String name;
		final int width;
		final int height;
		final Rectangle root;
		final int rootRows;
		final int skippedRows;
		/** Child rectangles; parents[i] is -1 for a root child, else an earlier child index. */
		final List<Rectangle> children = new ArrayList<>();
		final List<Integer> parents = new ArrayList<>();

		Spec(String name, int width, int height, Rectangle root, int rootRows, int skippedRows)
		{
			this.name = name;
			this.width = width;
			this.height = height;
			this.root = root;
			this.rootRows = rootRows;
			this.skippedRows = skippedRows;
		}

		Spec child(int parent, int x, int y, int w, int h)
		{
			children.add(new Rectangle(x, y, w, h));
			parents.add(parent);
			return this;
		}
	}

	/** A built menu tree for one probe. Rebuilt for every run so runs cannot interact. */
	private static final class Built
	{
		final ExactCoverageResolverTest.TestMenu root;
		final List<ExactCoverageResolverTest.TestMenu> menus = new ArrayList<>();
		final SubmenuTree tree;

		Built(Spec spec)
		{
			root = new ExactCoverageResolverTest.TestMenu(spec.root.x, spec.root.y, spec.root.width, spec.root.height);
			List<List<MenuEntry>> childEntries = new ArrayList<>();
			for (int i = 0; i < spec.children.size(); i++)
			{
				Rectangle r = spec.children.get(i);
				menus.add(new ExactCoverageResolverTest.TestMenu(r.x, r.y, r.width, r.height));
				childEntries.add(new ArrayList<>(Arrays.asList(ExactCoverageResolverTest.plainEntries(2))));
			}
			List<MenuEntry> rootEntries = new ArrayList<>();
			for (int i = 0; i < spec.children.size(); i++)
			{
				MenuEntry entry = ExactCoverageResolverTest.entry("Child " + i, menus.get(i));
				if (spec.parents.get(i) < 0)
				{
					rootEntries.add(entry);
				}
				else
				{
					childEntries.get(spec.parents.get(i)).add(entry);
				}
			}
			for (int i = 0; i < menus.size(); i++)
			{
				menus.get(i).entries = childEntries.get(i).toArray(new MenuEntry[0]);
			}
			// Native skips the last rows of a scrolled root, so padding goes first when rows are skipped.
			while (rootEntries.size() < spec.rootRows)
			{
				rootEntries.add(spec.skippedRows > 0 ? 0 : rootEntries.size(),
					ExactCoverageResolverTest.entry("Plain " + rootEntries.size(), null));
			}
			root.entries = rootEntries.toArray(new MenuEntry[0]);
			tree = SubmenuTree.capture(root, root.entries, spec.skippedRows);
		}
	}

	/** What native draws: the root, then the listed children in order, optionally altered. */
	private static final class Draw
	{
		final String name;
		final int[] chain;
		final boolean paintRoot;
		final int truncateLast;
		final Rectangle stray;
		final int throwAfter;

		Draw(String name, int[] chain, boolean paintRoot, int truncateLast, Rectangle stray, int throwAfter)
		{
			this.name = name;
			this.chain = chain;
			this.paintRoot = paintRoot;
			this.truncateLast = truncateLast;
			this.stray = stray;
			this.throwAfter = throwAfter;
		}

		static Draw of(int... chain)
		{
			return new Draw(chain.length == 0 ? "root" : "chain" + Arrays.toString(chain).replace(" ", ""),
				chain, true, 0, null, -1);
		}
	}

	private static final class StressCase implements Case
	{
		private final Spec spec;
		private final Draw draw;

		StressCase(Spec spec, Draw draw)
		{
			this.spec = spec;
			this.draw = draw;
		}

		@Override
		public String fingerprint(NativeSubmenuProbe probe)
		{
			Built built = new Built(spec);
			int[] pixels = ProbeSemanticsGoldenTest.noise(new Random(spec.name.hashCode() * 31L + draw.name.hashCode()),
				spec.width * spec.height);
			int[] before = pixels.clone();
			List<Rectangle> menus = new ArrayList<>();
			if (draw.paintRoot)
			{
				menus.add(ExactCoverageResolverTest.bounds(built.root));
			}
			for (int c = 0; c < draw.chain.length; c++)
			{
				Rectangle r = ExactCoverageResolverTest.bounds(built.menus.get(draw.chain[c]));
				if (c == draw.chain.length - 1 && draw.truncateLast > 0)
				{
					r = new Rectangle(r.x, r.y, r.width, Math.max(1, r.height - draw.truncateLast));
				}
				menus.add(r);
			}
			if (draw.stray != null)
			{
				menus.add(draw.stray);
			}
			String id = "stress:" + spec.name + ":" + draw.name + ":candidates=" + built.tree.candidateBounds.size();
			Runnable nativeDraw = () ->
			{
				if (draw.throwAfter >= 0)
				{
					ProbeSemanticsGoldenTest.paint(pixels, spec.width, spec.height,
						menus.subList(0, Math.min(menus.size(), draw.throwAfter)));
					throw new IllegalStateException("native draw failed");
				}
				ProbeSemanticsGoldenTest.paint(pixels, spec.width, spec.height, menus);
			};
			try
			{
				NativeSubmenuProbe.Result result = probe.samplePixels(pixels, spec.width, spec.height, built.tree, nativeDraw);
				return ProbeSemanticsGoldenTest.fingerprint(id, result, Arrays.equals(before, pixels));
			}
			catch (RuntimeException e)
			{
				boolean intact = Arrays.equals(before, pixels);
				// The same instance must then probe a normal frame exactly as a fresh one would.
				Built next = new Built(spec);
				int[] frame = ProbeSemanticsGoldenTest.noise(new Random(7), spec.width * spec.height);
				int[] frameBefore = frame.clone();
				List<Rectangle> rootOnly = Collections.singletonList(ExactCoverageResolverTest.bounds(next.root));
				NativeSubmenuProbe.Result after = probe.samplePixels(frame, spec.width, spec.height, next.tree,
					() -> ProbeSemanticsGoldenTest.paint(frame, spec.width, spec.height, rootOnly));
				return id + "|threw=" + e.getClass().getSimpleName() + ":" + e.getMessage() + "|bufferIntact=" + intact
					+ "|then " + ProbeSemanticsGoldenTest.fingerprint("followUp", after, Arrays.equals(frameBefore, frame));
			}
		}
	}

	static List<Case> cases()
	{
		List<Spec> specs = new ArrayList<>();
		// Identical stacked siblings, the live heavy shape: tall scrolled root, children to the right.
		for (int n : new int[]{2, 5, 20, 40})
		{
			Spec s = new Spec("identical" + n, 990, 592, new Rectangle(434, -40, 212, 1162), 60, 0);
			for (int i = 0; i < n; i++)
			{
				s.child(-1, 646, 300, 230, 97);
			}
			specs.add(s);
		}
		// Near-identical: same anchor, widths and heights within the 2px inset and just beyond it.
		{
			Spec s = new Spec("nearIdentical", 990, 592, new Rectangle(434, 0, 212, 592), 40, 0);
			int[] dw = {0, 1, 2, 3, 4, -1, -2, 5};
			for (int i = 0; i < dw.length; i++)
			{
				s.child(-1, 646, 330 + (i % 3), 239 + dw[i], 97 + (i % 2) * 3);
			}
			specs.add(s);
		}
		// Overlapping cascade: each sibling offset down by less than its height.
		{
			Spec s = new Spec("cascade", 990, 592, new Rectangle(300, 20, 180, 520), 30, 0);
			for (int i = 0; i < 25; i++)
			{
				s.child(-1, 480, 20 + i * 18, 200, 140);
			}
			specs.add(s);
		}
		// Clipped and offscreen children, both edges, and dormant ones mixed in.
		specs.add(new Spec("clipped", 990, 592, new Rectangle(700, 300, 200, 280), 12, 0)
			.child(-1, 900, 500, 200, 150)
			.child(-1, 900, 560, 120, 80)
			.child(-1, 990, 300, 100, 100)
			.child(-1, 1200, 100, 100, 100)
			.child(-1, 0, 0, 0, 0)
			.child(-1, 520, 590, 180, 60)
			.child(-1, 900, -50, 120, 80));
		specs.add(new Spec("negativeOrigin", 990, 592, new Rectangle(-30, -20, 160, 300), 8, 0)
			.child(-1, 130, -60, 150, 120)
			.child(-1, -200, 100, 170, 90)
			.child(-1, -400, 100, 150, 90));
		// Root/submenu intersections: overlaps across the margin, child inside root, child containing root.
		{
			Spec s = new Spec("rootOverlap", 990, 592, new Rectangle(400, 100, 200, 300), 16, 0);
			for (int overlap : new int[]{-3, -2, -1, 0, 1, 2, 3, 4, 10, 30})
			{
				s.child(-1, 600 - overlap, 120 + overlap * 3, 150, 90);
			}
			s.child(-1, 420, 150, 100, 60);
			s.child(-1, 350, 50, 400, 500);
			specs.add(s);
		}
		// Footprint scan limit: complex (no exclusive pixels) and candidateArea > 1,048,576.
		{
			Spec s = new Spec("footprintLimit", 1280, 720, new Rectangle(100, 0, 200, 720), 30, 0);
			for (int i = 0; i < 20; i++)
			{
				s.child(-1, 300, 200, 300, 250);
			}
			s.child(-1, 300, 470, 250, 100);
			specs.add(s);
		}
		// Candidate area limit (> 2,097,152 visible candidate pixels): UNSUPPORTED before any write.
		{
			Spec s = new Spec("areaLimit", 1280, 720, new Rectangle(100, 0, 200, 720), 40, 0);
			for (int i = 0; i < 30; i++)
			{
				s.child(-1, 300, 100, 300, 250);
			}
			specs.add(s);
		}
		// Scrolled roots: rows above the scroll offset are skipped, so their children are not candidates.
		specs.add(new Spec("scrolledSkip", 990, 592, new Rectangle(434, -300, 212, 1100), 60, 2)
			.child(-1, 646, 100, 200, 97)
			.child(-1, 646, 250, 200, 97)
			.child(-1, 646, 250, 200, 97)
			.child(-1, 0, 0, 0, 0)
			.child(-1, 646, 400, 210, 97));
		// Detached geometry: a child far from its root, and a grandchild far from its parent.
		specs.add(new Spec("detached", 990, 592, new Rectangle(100, 100, 150, 200), 6, 0)
			.child(-1, 700, 400, 150, 120)
			.child(0, 50, 450, 120, 100)
			.child(-1, 250, 120, 140, 100)
			.child(2, 390, 140, 120, 80)
			.child(3, 510, 160, 120, 80));
		// Tiny children: inset clamping min(MARGIN, (size - 1) / 2) at 1-5px.
		{
			Spec s = new Spec("tiny", 990, 592, new Rectangle(200, 200, 150, 200), 10, 0);
			for (int size = 1; size <= 5; size++)
			{
				s.child(-1, 350, 200 + size * 20, size, size);
				s.child(-1, 350, 200 + size * 20, size, 5);
			}
			specs.add(s);
		}
		// Root clipped at each canvas edge, including intersections with negative width the margin reaches.
		for (int edge = -4; edge <= 2; edge++)
		{
			specs.add(new Spec("rootLeftEdge" + edge, 400, 300, new Rectangle(edge - 150, 50, 150, 200), 4, 0)
				.child(-1, edge, 60, 120, 80)
				.child(-1, edge, 60, 120, 80));
			specs.add(new Spec("rootRightEdge" + edge, 400, 300, new Rectangle(400 - edge, 50, 150, 200), 4, 0)
				.child(-1, 400 - edge - 120, 60, 120, 80)
				.child(-1, 400 - edge - 120, 60, 120, 80));
		}
		specs.add(new Spec("rootBottomEdge", 400, 300, new Rectangle(100, 299, 150, 200), 4, 0)
			.child(-1, 250, 250, 120, 80));
		specs.add(new Spec("rootOffscreen", 400, 300, new Rectangle(500, 50, 150, 200), 4, 0)
			.child(-1, 300, 60, 120, 80));
		// Deep overlapping chain: four levels, siblings at each level.
		{
			Spec s = new Spec("deep", 990, 592, new Rectangle(50, 50, 150, 400), 10, 0);
			s.child(-1, 200, 60, 150, 200).child(-1, 200, 60, 150, 200);
			s.child(0, 350, 80, 150, 200).child(0, 350, 80, 150, 200).child(1, 350, 80, 150, 200);
			s.child(2, 500, 100, 150, 200).child(2, 500, 100, 150, 200);
			s.child(5, 650, 120, 150, 200);
			specs.add(s);
		}

		List<Case> cases = new ArrayList<>();
		for (Spec spec : specs)
		{
			List<Draw> draws = new ArrayList<>();
			draws.add(Draw.of());
			draws.add(new Draw("none", new int[0], false, 0, null, -1));
			int n = spec.children.size();
			for (int i = 0; i < n; i++)
			{
				Rectangle r = spec.children.get(i);
				if (r.width == 0)
				{
					continue;
				}
				if (spec.parents.get(i) < 0 && (n <= 12 || i == 0 || i == n / 2 || i == n - 1))
				{
					draws.add(Draw.of(i));
				}
				else if (spec.parents.get(i) >= 0)
				{
					List<Integer> chain = new ArrayList<>();
					for (int node = i; node >= 0; node = spec.parents.get(node))
					{
						chain.add(0, node);
					}
					draws.add(Draw.of(chain.stream().mapToInt(Integer::intValue).toArray()));
				}
			}
			int first = -1;
			for (int i = 0; i < n && first < 0; i++)
			{
				first = spec.children.get(i).width > 0 && spec.parents.get(i) < 0 ? i : -1;
			}
			if (first >= 0)
			{
				draws.add(new Draw("partial" + first, new int[]{first}, true, 3, null, -1));
				draws.add(new Draw("stray" + first, new int[]{first}, true, 0, new Rectangle(spec.width - 7, spec.height - 7, 4, 4), -1));
				draws.add(new Draw("childNoRoot" + first, new int[]{first}, false, 0, null, -1));
				draws.add(new Draw("throwBefore" + first, new int[]{first}, true, 0, null, 0));
				draws.add(new Draw("throwAfterRoot" + first, new int[]{first}, true, 0, null, 1));
			}
			for (Draw draw : draws)
			{
				cases.add(new StressCase(spec, draw));
			}
		}
		return cases;
	}
}
