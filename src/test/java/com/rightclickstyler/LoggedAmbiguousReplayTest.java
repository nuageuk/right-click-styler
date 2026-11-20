package com.rightclickstyler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Replays every distinct ambiguous probe logged in the manual GPU traces.
 *
 * <p>The logs hold each frame's candidate rectangles and the probe's per-candidate evidence
 * counts (exclusive and shared witnesses, and how many of each changed). The replay rebuilds
 * that geometry and draws one hypothesis at a time: root only, or root plus one candidate. A
 * hypothesis is ground truth only when it reproduces every logged count exactly, which
 * identifies what native drew independently of either resolver. The exact-coverage reading is
 * then judged against that ground truth, and so is the authoritative gate built on it.
 *
 * <p>Logs: {@code trace} and {@code step1} are the 17 November traces; {@code exact1} is the
 * 18 November hardened GPU session that ran the exact-coverage shadow (its 32 ambiguous
 * probes cover 30 distinct geometries).
 */
public class LoggedAmbiguousReplayTest
{
	/**
	 * Records whose logged evidence is reproduced exactly by two hypotheses, so no reading of
	 * this marking can tell what native drew. Frame 2099's candidates 38 and 58 are
	 * 619,330,239x97 and 619,330,241x97: the 2px difference lies inside the 2px inset.
	 */
	private static final Set<String> INDISTINGUISHABLE = new HashSet<>(Arrays.asList("exact1|2099"));

	@Test
	public void exactCoverageResolvesEveryIndependentlyDistinguishableLoggedFrame() throws Exception
	{
		List<String[]> records = records();
		assertEquals(70, records.size());
		Set<String> latestGeometries = new HashSet<>();
		int latest = 0;
		int resolvedCorrectly = 0;
		Set<String> indistinguishable = new TreeSet<>();
		for (String[] record : records)
		{
			String key = record[0] + "|" + record[1];
			String name = record[0] + " frame " + record[1] + " (" + record[2] + ")";
			if (record[0].equals("exact1"))
			{
				latest++;
				latestGeometries.add(record[3] + "|" + record[4] + "|" + record[5] + "|" + record[6]);
			}
			Replay replay = new Replay(record);
			SubmenuTree tree = SubmenuTree.capture(replay.root);
			List<Integer> truths = new ArrayList<>();
			List<NativeSubmenuProbe.Result> truthResults = new ArrayList<>();
			for (int hypothesis : replay.hypotheses())
			{
				NativeSubmenuProbe.Result result = replay.probe(hypothesis);
				assertTrue(name + ": geometry reconstruction (witness counts)",
					Arrays.equals(replay.witnesses, result.witnesses)
						&& Arrays.equals(replay.sharedWitnesses, result.sharedWitnesses));
				if (Arrays.equals(replay.changed, result.changed)
					&& Arrays.equals(replay.sharedChanged, result.sharedChanged))
				{
					truths.add(hypothesis);
					truthResults.add(result);
				}
			}
			for (NativeSubmenuProbe.Result result : truthResults)
			{
				assertEquals(name + ": the previous resolver is still ambiguous",
					NativeSubmenuProbe.State.AMBIGUOUS, result.state);
				assertTrue(name, result.framebufferVerified);
			}
			if (truths.size() != 1)
			{
				indistinguishable.add(key);
				for (NativeSubmenuProbe.Result result : truthResults)
				{
					assertEquals(name + ": no independent ground truth, so never resolved: " + result.exact,
						ExactCoverageResolver.State.AMBIGUOUS, result.exact.state);
					assertEquals(name, truths.size(), result.exact.explainingChains);
					assertEquals(name + ": stays native", MenuOverlay.SubmenuOutcome.FALLBACK_AMBIGUOUS,
						MenuOverlay.authoritativeRejection(result, tree));
				}
				continue;
			}
			int truth = truths.get(0);
			NativeSubmenuProbe.Result result = truthResults.get(0);
			int[] expected = truth < 0 ? new int[0] : new int[]{truth};
			assertEquals(name + ": resolves the ground truth " + Arrays.toString(expected) + " " + result.exact,
				ExactCoverageResolver.State.RESOLVED, result.exact.state);
			assertTrue(name + ": ground truth " + Arrays.toString(expected) + " but " + result.exact,
				Arrays.equals(expected, result.exact.chain));
			assertEquals(name, MenuOverlay.ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED, MenuOverlay.classifyExact(result));
			assertEquals(name + ": the authoritative gate passes the ground truth", null,
				MenuOverlay.authoritativeRejection(result, tree));
			resolvedCorrectly++;
		}
		assertEquals("the latest session's ambiguous probes", 32, latest);
		assertEquals("the latest session's distinct geometries", 30, latestGeometries.size());
		assertEquals("only these records lack an independent ground truth", INDISTINGUISHABLE, indistinguishable);
		assertEquals(records.size() - INDISTINGUISHABLE.size(), resolvedCorrectly);
		System.out.println("REPLAY records=" + records.size() + " exactResolvedCorrectly=" + resolvedCorrectly
			+ " indistinguishable=" + indistinguishable + " latestProbes=" + latest
			+ " latestGeometries=" + latestGeometries.size());
	}

	/**
	 * Feasibility of a cheaper probe, without changing the probe: mark only each candidate's
	 * outermost rows and columns, every {@code step} pixels, and give the exact-coverage reading
	 * that sparse evidence. Distinct rectangles always differ along an edge of one of them.
	 */
	@Test
	public void sparseEdgeMarksPreserveEveryLoggedResolution() throws Exception
	{
		int sentinel = 0x13579BDF;
		StringBuilder report = new StringBuilder();
		for (int step : new int[]{1, 2, 4, 8, 16})
		{
			int correct = 0;
			long marks = 0;
			long areaMarks = 0;
			for (String[] record : records())
			{
				if (INDISTINGUISHABLE.contains(record[0] + "|" + record[1]))
				{
					continue; // no ground truth to judge a sparse reading against
				}
				Replay replay = new Replay(record);
				int truth = -1;
				for (int hypothesis : replay.hypotheses())
				{
					NativeSubmenuProbe.Result result = replay.probe(hypothesis);
					if (Arrays.equals(replay.changed, result.changed) && Arrays.equals(replay.sharedChanged, result.sharedChanged))
					{
						truth = hypothesis;
						areaMarks += sum(result.witnesses) + sum(result.sharedWitnesses);
					}
				}
				SubmenuTree tree = SubmenuTree.capture(replay.root);
				java.awt.Rectangle canvas = new java.awt.Rectangle(0, 0, replay.width, replay.height);
				java.awt.Rectangle root = tree.nodes.get(0).bounds.intersection(canvas);
				int[] pixels = new int[replay.width * replay.height];
				boolean[] dormant = new boolean[tree.candidateBounds.size()];
				int[] positions = new int[pixels.length];
				int count = 0;
				for (int c = 0; c < dormant.length; c++)
				{
					java.awt.Rectangle v = tree.candidateBounds.get(c).intersection(canvas);
					dormant[c] = v.width <= 0 || v.height <= 0;
					if (dormant[c])
					{
						continue;
					}
					for (int y = v.y; y < v.y + v.height; y++)
					{
						boolean edgeRow = y == v.y || y == v.y + v.height - 1;
						for (int x = v.x; x < v.x + v.width; x++)
						{
							boolean edgeColumn = x == v.x || x == v.x + v.width - 1;
							boolean sampled = edgeRow && edgeColumn || edgeRow && (x - v.x) % step == 0
								|| edgeColumn && (y - v.y) % step == 0;
							int position = y * replay.width + x;
							if (sampled && pixels[position] != sentinel && !(x >= root.x - 2 && x < root.x + root.width + 2
								&& y >= root.y - 2 && y < root.y + root.height + 2))
							{
								pixels[position] = sentinel;
								positions[count++] = position;
							}
						}
					}
				}
				marks += count;
				replay.fill(pixels, ExactCoverageResolverTest.bounds(replay.root));
				if (truth >= 0)
				{
					replay.fill(pixels, ExactCoverageResolverTest.bounds(replay.candidates.get(truth)));
				}
				ExactCoverageResolver.Outcome outcome = new ExactCoverageResolver().resolve(pixels, replay.width,
					replay.height, sentinel, root, 2, tree.candidateBounds, tree.candidateParents, dormant,
					new int[][]{positions}, new int[]{count});
				int[] expected = truth < 0 ? new int[0] : new int[]{truth};
				if (outcome.state == ExactCoverageResolver.State.RESOLVED)
				{
					assertTrue("step " + step + " frame " + record[1] + ": never a wrong chain",
						Arrays.equals(expected, outcome.chain));
					correct++;
				}
			}
			int judged = records().size() - INDISTINGUISHABLE.size();
			report.append(String.format(" step=%d resolved=%d/%d marks/frame=%d (area marking %d);",
				step, correct, judged, marks / judged, areaMarks / judged));
			if (step <= 4)
			{
				assertEquals("step " + step, judged, correct);
			}
		}
		System.out.println("SPARSE" + report);
	}

	private static long sum(int[] values)
	{
		long total = 0;
		for (int value : values)
		{
			total += value;
		}
		return total;
	}

	static List<String[]> records() throws Exception
	{
		List<String[]> records = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			LoggedAmbiguousReplayTest.class.getResourceAsStream("logged-ambiguous-probes.txt"),
			StandardCharsets.UTF_8)))
		{
			for (String line; (line = reader.readLine()) != null; )
			{
				if (!line.startsWith("#") && !line.isEmpty())
				{
					records.add(line.split("\\|", -1));
				}
			}
		}
		return records;
	}

	/** One logged frame's geometry: a root whose entries open the logged candidates in order. */
	static final class Replay
	{
		final ExactCoverageResolverTest.TestMenu root;
		final List<ExactCoverageResolverTest.TestMenu> candidates = new ArrayList<>();
		final int width;
		final int height;
		final int[] witnesses;
		final int[] changed;
		final int[] sharedWitnesses;
		final int[] sharedChanged;

		Replay(String[] record)
		{
			int[] rootBounds = ints(record[3], ",");
			String[] canvas = record[4].split("x");
			width = Integer.parseInt(canvas[0]);
			height = Integer.parseInt(canvas[1]);
			root = new ExactCoverageResolverTest.TestMenu(rootBounds[0], rootBounds[1], rootBounds[2], rootBounds[3]);
			String[] bounds = record[5].split(";");
			MenuEntry[] entries = new MenuEntry[bounds.length];
			for (int i = 0; i < bounds.length; i++)
			{
				int[] b = ints(bounds[i], ",");
				ExactCoverageResolverTest.TestMenu child = new ExactCoverageResolverTest.TestMenu(b[0], b[1], b[2], b[3]);
				child.entries = ExactCoverageResolverTest.plainEntries(Math.max(1, (b[3] - 22) / MenuRenderer.ROW_HEIGHT));
				candidates.add(child);
				entries[i] = ExactCoverageResolverTest.entry("Candidate " + i, child);
			}
			root.entries = entries;
			witnesses = ints(record[7], ",");
			changed = ints(record[8], ",");
			sharedWitnesses = ints(record[9], ",");
			sharedChanged = ints(record[10], ",");
		}

		/** Root only (-1), plus each candidate that changed any of its evidence. */
		List<Integer> hypotheses()
		{
			List<Integer> hypotheses = new ArrayList<>();
			hypotheses.add(-1);
			for (int i = 0; i < candidates.size(); i++)
			{
				if (changed[i] > 0 || sharedChanged[i] > 0)
				{
					hypotheses.add(i);
				}
			}
			return hypotheses;
		}

		NativeSubmenuProbe.Result probe(int drawn)
		{
			int[] pixels = new int[width * height];
			Menu[] chain = drawn < 0 ? new Menu[0] : new Menu[]{candidates.get(drawn)};
			SubmenuTree tree = SubmenuTree.capture(root);
			NativeSubmenuProbe probe = new NativeSubmenuProbe();
			return probe.samplePixels(pixels, width, height, tree, () ->
			{
				fill(pixels, ExactCoverageResolverTest.bounds(root));
				for (Menu menu : chain)
				{
					fill(pixels, ExactCoverageResolverTest.bounds(menu));
				}
			});
		}

		private void fill(int[] pixels, java.awt.Rectangle rect)
		{
			java.awt.Rectangle visible = rect.intersection(new java.awt.Rectangle(0, 0, width, height));
			for (int y = visible.y; y < visible.y + visible.height; y++)
			{
				Arrays.fill(pixels, y * width + visible.x, y * width + visible.x + visible.width, 0xFF5D5447);
			}
		}
	}

	static int[] ints(String text, String separator)
	{
		if (text.isEmpty())
		{
			return new int[0];
		}
		String[] parts = text.split(separator);
		int[] values = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
		{
			values[i] = Integer.parseInt(parts[i].trim());
		}
		return values;
	}
}
