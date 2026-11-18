package com.rightclickstyler;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.runelite.api.MenuEntry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Pins the probe's detection semantics so performance work cannot change them silently.
 *
 * <p>Every case is a deterministic geometry and native draw: each logged GPU geometry under each
 * of its replay hypotheses, plus seeded synthetic trees (overlapping and identical siblings,
 * nested children, dormant and offscreen candidates, clipped roots, several canvas sizes) under
 * well-formed, partial, stray, missing and geometry-changing draws. Each result is reduced to
 * a fingerprint of everything that decides or explains a frame: state, reason, chain, every
 * evidence count, the footprint reading and the full exact-coverage outcome. Timings and
 * allocation sizes are excluded.
 *
 * <p>The cases run through one reused probe instance in sequence, as the live overlay does, so
 * state carried between frames (buffers, masks, coverage) is exercised, and each one is also
 * run on a fresh instance; both must agree. The framebuffer must be restored bit for bit.
 *
 * <p>The golden file was generated from the implementation at 6d9c721 plus the working tree of
 * 18 November 2026, before any probe optimisation. Regenerate it only for an intended semantic
 * change: {@code -PwriteProbeGolden=<absolute path>}.
 */
public class ProbeSemanticsGoldenTest
{
	private static final String GOLDEN = "probe-semantics-golden.txt";
	private static final int SYNTHETIC_CASES = 600;
	private static final long SEED = 0x5EED_2026_1118L;
	private static final int[][] CANVASES = {{990, 592}, {990, 592}, {990, 592}, {765, 503}, {1280, 720}, {420, 300}};

	private static final String STRESS_GOLDEN = "probe-stress-golden.txt";

	/**
	 * Every optimisation level must reproduce the golden fingerprints of the original
	 * implementation. With {@code -PwriteProbeGolden=<file>} the seeded corpus is written from
	 * level 0 instead; {@code -PwriteProbeGolden=<file>|<stress file>} also writes the stress corpus.
	 */
	@Test
	public void probeSemanticsMatchGolden() throws Exception
	{
		String write = System.getProperty("rightclickstyler.writeProbeGolden", "");
		if (!write.isEmpty())
		{
			String[] files = write.split("\\|");
			List<String> seeded = run(0, corpus());
			Files.write(Paths.get(files[0]), seeded, StandardCharsets.UTF_8);
			System.out.println("PROBEGOLDEN wrote " + seeded.size() + " cases to " + files[0]);
			if (files.length > 1)
			{
				List<String> stress = run(0, stressCorpus());
				Files.write(Paths.get(files[1]), stress, StandardCharsets.UTF_8);
				System.out.println("PROBEGOLDEN wrote " + stress.size() + " cases to " + files[1]);
			}
			return;
		}
		List<Case> corpus = corpus();
		for (int level = 0; level <= SubmenuPrototypeProbe.MAX_OPTIMISATION; level++)
		{
			compare("level " + level + " seeded corpus", golden(GOLDEN), run(level, corpus));
		}
	}

	@Test
	public void stressCorpusMatchesGolden() throws Exception
	{
		assumeTrue(System.getProperty("rightclickstyler.writeProbeGolden", "").isEmpty());
		List<Case> corpus = stressCorpus();
		for (int level = 0; level <= SubmenuPrototypeProbe.MAX_OPTIMISATION; level++)
		{
			compare("level " + level + " stress corpus", golden(STRESS_GOLDEN), run(level, corpus));
		}
	}

	/** Sanity: the stress corpus reaches the limits and failure paths it exists for. */
	@Test
	public void stressCorpusExercisesItsEdgeCases() throws Exception
	{
		String all = String.join("\n", run(0, stressCorpus()));
		for (String expected : new String[]{
			"footprint scan limit exceeded", "candidate pixel area limit exceeded", "threw=IllegalStateException",
			"ambiguous root geometry", "native draw missed buffer", "|ACTIVE|", "|NO_CHILD|", "exact=RESOLVED"})
		{
			assertTrue("stress corpus never produced " + expected, all.contains(expected));
		}
		assertTrue(!all.contains("bufferIntact=false"));
	}

	private static void compare(String what, List<String> golden, List<String> actual)
	{
		int mismatches = 0;
		StringBuilder report = new StringBuilder();
		for (int i = 0; i < Math.max(golden.size(), actual.size()); i++)
		{
			String expected = i < golden.size() ? golden.get(i) : "<missing>";
			String got = i < actual.size() ? actual.get(i) : "<missing>";
			if (!expected.equals(got) && mismatches++ < 10)
			{
				report.append("\n  expected ").append(expected).append("\n  actual   ").append(got);
			}
		}
		if (mismatches > 0)
		{
			fail(what + ": " + mismatches + " of " + golden.size() + " probe fingerprints differ from the golden file:" + report);
		}
	}

	/** Sanity: the corpus covers every decision path the probe has, so the golden means something. */
	@Test
	public void corpusExercisesEveryProbeOutcome() throws Exception
	{
		String all = String.join("\n", run(0, corpus()));
		for (String expected : new String[]{
			"|NO_CHILD|", "|ACTIVE|", "|AMBIGUOUS|", "|FAILURE|", "|GEOMETRY_CHANGED|",
			"footprint matched", "native draw missed buffer", "ambiguous geometry index=",
			"exact=RESOLVED", "exact=AMBIGUOUS", "exact=UNEXPLAINED", "exact=UNEVIDENCED"})
		{
			assertTrue("corpus never produced " + expected, all.contains(expected));
		}
	}

	static List<Case> stressCorpus()
	{
		List<Case> cases = new ArrayList<>();
		for (ProbeStressCorpus.Case c : ProbeStressCorpus.cases())
		{
			cases.add(c::fingerprint);
		}
		return cases;
	}

	static List<Case> corpus() throws Exception
	{
		List<Case> cases = new ArrayList<>();
		for (String[] record : LoggedAmbiguousReplayTest.records())
		{
			LoggedAmbiguousReplayTest.Replay replay = new LoggedAmbiguousReplayTest.Replay(record);
			for (int hypothesis : replay.hypotheses())
			{
				cases.add(new LoggedCase(record, hypothesis));
			}
		}
		for (int i = 0; i < SYNTHETIC_CASES; i++)
		{
			cases.add(new SyntheticCase(i));
		}
		return cases;
	}

	/** One reused instance in sequence, as live, each case checked against a fresh instance. */
	static List<String> run(int level, List<Case> cases)
	{
		SubmenuPrototypeProbe shared = probe(level);
		List<String> lines = new ArrayList<>();
		for (Case c : cases)
		{
			String reused = c.fingerprint(shared);
			assertTrue("level " + level + " left scratch state dirty after " + reused, shared.scratchClean());
			SubmenuPrototypeProbe fresh = probe(level);
			String isolated = c.fingerprint(fresh);
			assertTrue("level " + level + " left scratch state dirty after " + isolated, fresh.scratchClean());
			assertEquals("level " + level + ": reused and fresh probe instances disagree", isolated, reused);
			lines.add(reused);
		}
		return lines;
	}

	static SubmenuPrototypeProbe probe(int level)
	{
		SubmenuPrototypeProbe probe = new SubmenuPrototypeProbe();
		probe.requireExact(true);
		probe.setOptimisation(level);
		return probe;
	}

	private static List<String> golden(String name) throws Exception
	{
		List<String> lines = new ArrayList<>();
		InputStream in = ProbeSemanticsGoldenTest.class.getResourceAsStream(name);
		assertNotNull("missing golden resource " + name, in);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			for (String line; (line = reader.readLine()) != null; )
			{
				lines.add(line);
			}
		}
		return lines;
	}

	static String fingerprint(String id, SubmenuPrototypeProbe.Result r, boolean restoredExactly)
	{
		ExactCoverageResolver.Outcome e = r.exact;
		return id + "|" + r.state + "|" + r.reason
			+ "|chain=" + Arrays.toString(r.activeChain)
			+ "|w=" + Arrays.toString(r.witnesses) + "|c=" + Arrays.toString(r.changed)
			+ "|sw=" + Arrays.toString(r.sharedWitnesses) + "|sc=" + Arrays.toString(r.sharedChanged)
			+ "|fp=" + r.footprintMatches + "/" + r.footprintChangedPixels + "/" + rect(r.footprintBounds)
			+ "|exact=" + e.state + " chain=" + Arrays.toString(e.chain) + " explaining=" + e.explainingChains
			+ " covered=" + Arrays.toString(e.covered) + " changed=" + e.changedPixels
			+ "|verified=" + r.framebufferVerified + "|restored=" + r.restored
			+ "|visible=" + r.visibleCandidates + "|area=" + r.candidatePixels
			+ "|passes=" + r.drawPasses + "|bufferIntact=" + restoredExactly;
	}

	private static String rect(Rectangle r)
	{
		return r == null ? "-" : r.x + "," + r.y + "," + r.width + "," + r.height;
	}

	interface Case
	{
		String fingerprint(SubmenuPrototypeProbe probe);
	}

	/** A logged geometry with one replay hypothesis drawn: root only (-1) or root plus one candidate. */
	private static final class LoggedCase implements Case
	{
		private final String[] record;
		private final int hypothesis;

		LoggedCase(String[] record, int hypothesis)
		{
			this.record = record;
			this.hypothesis = hypothesis;
		}

		@Override
		public String fingerprint(SubmenuPrototypeProbe probe)
		{
			LoggedAmbiguousReplayTest.Replay replay = new LoggedAmbiguousReplayTest.Replay(record);
			int[] pixels = noise(new Random(record[1].hashCode() * 31L + hypothesis), replay.width * replay.height);
			int[] before = pixels.clone();
			List<Rectangle> drawn = new ArrayList<>();
			drawn.add(ExactCoverageResolverTest.bounds(replay.root));
			if (hypothesis >= 0)
			{
				drawn.add(ExactCoverageResolverTest.bounds(replay.candidates.get(hypothesis)));
			}
			SubmenuTree tree = SubmenuTree.capture(replay.root);
			SubmenuPrototypeProbe.Result result = probe.samplePixels(pixels, replay.width, replay.height, tree,
				() -> paint(pixels, replay.width, replay.height, drawn));
			return ProbeSemanticsGoldenTest.fingerprint("log:" + record[0] + ":" + record[1] + ":h" + hypothesis,
				result, Arrays.equals(before, pixels));
		}
	}

	/** A seeded synthetic tree and draw; rebuilt from its seed for every run so runs cannot interact. */
	private static final class SyntheticCase implements Case
	{
		private static final String[] MODES = {"rootOnly", "chain", "chain", "partial", "stray", "none", "moved"};
		private final int index;

		SyntheticCase(int index)
		{
			this.index = index;
		}

		@Override
		public String fingerprint(SubmenuPrototypeProbe probe)
		{
			Random random = new Random(SEED * 1_000_003L + index);
			int[] canvas = CANVASES[random.nextInt(CANVASES.length)];
			int width = canvas[0];
			int height = canvas[1];
			int rootWidth = 110 + random.nextInt(150);
			int rootHeight = 40 + random.nextInt(Math.max(1, height + 150));
			int rootX = -20 + random.nextInt(Math.max(1, width - 60));
			int rootY = -15 + random.nextInt(Math.max(1, height - 30));
			ExactCoverageResolverTest.TestMenu root = new ExactCoverageResolverTest.TestMenu(rootX, rootY, rootWidth, rootHeight);
			int childCount = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(random.nextBoolean() ? 6 : 40);
			List<ExactCoverageResolverTest.TestMenu> live = new ArrayList<>();
			List<List<ExactCoverageResolverTest.TestMenu>> grandchildren = new ArrayList<>();
			List<MenuEntry> rootEntries = new ArrayList<>();
			ExactCoverageResolverTest.TestMenu previous = null;
			for (int i = 0; i < childCount; i++)
			{
				ExactCoverageResolverTest.TestMenu child;
				if (random.nextInt(4) == 0)
				{
					child = new ExactCoverageResolverTest.TestMenu(0, 0, 0, 0);
				}
				else if (previous != null && random.nextInt(3) == 0)
				{
					// Stacked siblings: identical, or the same anchor with a small size difference.
					int dw = random.nextBoolean() ? 0 : random.nextInt(7) - 3;
					int dh = random.nextBoolean() ? 0 : random.nextInt(31) - 15;
					child = new ExactCoverageResolverTest.TestMenu(previous.getMenuX(), previous.getMenuY() + (random.nextBoolean() ? 0 : random.nextInt(40)),
						Math.max(1, previous.getMenuWidth() + dw), Math.max(1, previous.getMenuHeight() + dh));
				}
				else
				{
					int w = 70 + random.nextInt(200);
					int h = 25 + random.nextInt(280);
					boolean left = random.nextInt(4) == 0;
					int x = left ? rootX - w + random.nextInt(4) : rootX + rootWidth - random.nextInt(4);
					int y = rootY + random.nextInt(Math.max(1, rootHeight)) - random.nextInt(60);
					child = new ExactCoverageResolverTest.TestMenu(x, y, w, h);
				}
				List<ExactCoverageResolverTest.TestMenu> nested = new ArrayList<>();
				List<MenuEntry> childEntries = new ArrayList<>(Arrays.asList(
					ExactCoverageResolverTest.plainEntries(1 + random.nextInt(4))));
				if (child.getMenuWidth() > 0 && random.nextInt(6) == 0)
				{
					int nestedCount = 1 + random.nextInt(3);
					for (int n = 0; n < nestedCount; n++)
					{
						ExactCoverageResolverTest.TestMenu grandchild = random.nextInt(3) == 0
							? new ExactCoverageResolverTest.TestMenu(0, 0, 0, 0)
							: new ExactCoverageResolverTest.TestMenu(child.getMenuX() + child.getMenuWidth() - random.nextInt(3),
								child.getMenuY() + random.nextInt(Math.max(1, child.getMenuHeight())),
								60 + random.nextInt(150), 25 + random.nextInt(150));
						grandchild.entries = ExactCoverageResolverTest.plainEntries(1 + random.nextInt(3));
						nested.add(grandchild);
						childEntries.add(ExactCoverageResolverTest.entry("Nested " + n, grandchild));
					}
				}
				child.entries = childEntries.toArray(new MenuEntry[0]);
				rootEntries.add(ExactCoverageResolverTest.entry("Candidate " + i, child));
				if (random.nextBoolean())
				{
					rootEntries.add(ExactCoverageResolverTest.entry("Plain " + i, null));
				}
				live.add(child);
				grandchildren.add(nested);
				if (child.getMenuWidth() > 0)
				{
					previous = child;
				}
			}
			if (rootEntries.isEmpty())
			{
				rootEntries.addAll(Arrays.asList(ExactCoverageResolverTest.plainEntries(1 + random.nextInt(8))));
			}
			root.entries = rootEntries.toArray(new MenuEntry[0]);

			String mode = MODES[random.nextInt(MODES.length)];
			List<ExactCoverageResolverTest.TestMenu> chain = new ArrayList<>();
			List<Integer> open = new ArrayList<>();
			for (int i = 0; i < live.size(); i++)
			{
				if (live.get(i).getMenuWidth() > 0)
				{
					open.add(i);
				}
			}
			if (!mode.equals("rootOnly") && !open.isEmpty())
			{
				int pick = open.get(random.nextInt(open.size()));
				chain.add(live.get(pick));
				List<ExactCoverageResolverTest.TestMenu> nested = grandchildren.get(pick);
				if (!nested.isEmpty() && random.nextBoolean())
				{
					ExactCoverageResolverTest.TestMenu grandchild = nested.get(random.nextInt(nested.size()));
					if (grandchild.getMenuWidth() > 0)
					{
						chain.add(grandchild);
					}
				}
			}
			List<Rectangle> drawn = new ArrayList<>();
			drawn.add(ExactCoverageResolverTest.bounds(root));
			for (ExactCoverageResolverTest.TestMenu menu : chain)
			{
				drawn.add(ExactCoverageResolverTest.bounds(menu));
			}
			if (mode.equals("partial") && drawn.size() > 1)
			{
				Rectangle last = drawn.get(drawn.size() - 1);
				drawn.set(drawn.size() - 1, new Rectangle(last.x, last.y, last.width, Math.max(1, last.height - 1 - random.nextInt(8))));
			}
			if (mode.equals("stray"))
			{
				drawn.add(new Rectangle(random.nextInt(width), random.nextInt(height), 1 + random.nextInt(6), 1 + random.nextInt(6)));
			}
			ExactCoverageResolverTest.TestMenu moved = mode.equals("moved") && !chain.isEmpty() ? chain.get(0) : null;
			boolean paint = !mode.equals("none");

			int[] pixels = noise(random, width * height);
			int[] before = pixels.clone();
			SubmenuTree tree = SubmenuTree.capture(root);
			SubmenuPrototypeProbe.Result result = probe.samplePixels(pixels, width, height, tree, () ->
			{
				if (paint)
				{
					paint(pixels, width, height, drawn);
				}
				if (moved != null)
				{
					moved.setBounds(moved.getMenuX() + 1, moved.getMenuY(), moved.getMenuWidth(), moved.getMenuHeight());
				}
			});
			return ProbeSemanticsGoldenTest.fingerprint("syn:" + index + ":" + mode + ":" + width + "x" + height
				+ ":candidates=" + tree.candidateBounds.size(), result, Arrays.equals(before, pixels));
		}
	}

	static int[] noise(Random random, int size)
	{
		int[] pixels = new int[size];
		for (int i = 0; i < size; i++)
		{
			pixels[i] = random.nextInt();
		}
		return pixels;
	}

	/** Native draw stand-in: each menu overwrites its visible rectangle, in draw order. */
	static void paint(int[] pixels, int width, int height, List<Rectangle> menus)
	{
		Rectangle canvas = new Rectangle(0, 0, width, height);
		for (int m = 0; m < menus.size(); m++)
		{
			Rectangle visible = menus.get(m).intersection(canvas);
			if (visible.isEmpty())
			{
				continue;
			}
			int color = 0xFF000000 | (0x5D5447 + m * 0x0A0B0C);
			for (int y = visible.y; y < visible.y + visible.height; y++)
			{
				Arrays.fill(pixels, y * width + visible.x, y * width + visible.x + visible.width, color);
			}
		}
	}
}
