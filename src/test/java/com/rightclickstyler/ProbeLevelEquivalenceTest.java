package com.rightclickstyler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every optimisation level must leave the same intermediate evidence as the original
 * implementation, not only the same results: for every pass of every seeded, logged and stress
 * case, (1) the framebuffer as marked immediately before the native draw is identical, so the
 * same pixels carry the sentinel, and (2) the set of positions handed to the exact resolver is
 * identical, whatever duplicates or ordering a level removes. The resolver's outcome depends on
 * its input only as a set (it deduplicates through its mask and quantifies over all changed
 * positions), so (2) with an identical framebuffer means it reads identical evidence.
 */
public class ProbeLevelEquivalenceTest
{
	@Test
	public void everyLevelMarksTheSamePixelsAndFeedsTheResolverTheSameSet() throws Exception
	{
		List<ProbeSemanticsGoldenTest.Case> cases = new ArrayList<>(ProbeSemanticsGoldenTest.corpus());
		cases.addAll(ProbeSemanticsGoldenTest.stressCorpus());
		List<List<String>> baseline = observe(0, cases);
		int resolverPasses = 0;
		int dedupedPasses = 0;
		for (List<String> passes : baseline)
		{
			for (String pass : passes)
			{
				resolverPasses += pass.contains("resolver=") ? 1 : 0;
			}
		}
		for (int level = 1; level <= SubmenuPrototypeProbe.MAX_OPTIMISATION; level++)
		{
			List<List<String>> observed = observe(level, cases);
			for (int i = 0; i < cases.size(); i++)
			{
				List<String> expected = strip(baseline.get(i));
				List<String> actual = strip(observed.get(i));
				assertEquals("level " + level + " case " + i + " intermediate evidence differs", expected, actual);
				if (level == SubmenuPrototypeProbe.MAX_OPTIMISATION)
				{
					for (int p = 0; p < observed.get(i).size(); p++)
					{
						dedupedPasses += total(observed.get(i).get(p)) < total(baseline.get(i).get(p)) ? 1 : 0;
					}
				}
			}
		}
		assertTrue("corpus must reach the exact resolver", resolverPasses > 1_000);
		assertTrue("the deduplicating level must actually remove duplicate resolver positions", dedupedPasses > 0);
		System.out.println("PROBEEQUIV cases=" + cases.size() + " resolverPasses=" + resolverPasses
			+ " passesWithFewerResolverPositionsAtMax=" + dedupedPasses);
	}

	/** Per case, one line per probe pass: marked-frame hash, and resolver input set (hash, size) plus raw total. */
	private static List<List<String>> observe(int level, List<ProbeSemanticsGoldenTest.Case> cases)
	{
		List<List<String>> all = new ArrayList<>();
		List<String> current = new ArrayList<>();
		SubmenuPrototypeProbe.observer = new SubmenuPrototypeProbe.Observer()
		{
			@Override
			public void beforeNativeDraw(int[] pixels, int width, int height)
			{
				long hash = 0xcbf29ce484222325L;
				for (int i = 0; i < width * height; i++)
				{
					hash = (hash ^ pixels[i]) * 0x100000001b3L;
				}
				current.add("marked=" + Long.toHexString(hash));
			}

			@Override
			public void resolverInput(int[][] lists, int[] sizes)
			{
				BitSet union = new BitSet();
				long total = 0;
				for (int l = 0; l < lists.length; l++)
				{
					for (int j = 0; j < sizes[l]; j++)
					{
						union.set(lists[l][j]);
					}
					total += sizes[l];
				}
				String last = current.remove(current.size() - 1);
				current.add(last + " resolver=" + union.hashCode() + "/" + union.cardinality() + " total=" + total);
			}
		};
		try
		{
			SubmenuPrototypeProbe probe = ProbeSemanticsGoldenTest.probe(level);
			for (ProbeSemanticsGoldenTest.Case c : cases)
			{
				current.clear();
				c.fingerprint(probe);
				all.add(new ArrayList<>(current));
			}
		}
		finally
		{
			SubmenuPrototypeProbe.observer = null;
		}
		return all;
	}

	/** Drops the raw position total, which is the one thing a deduplicating level may change. */
	private static List<String> strip(List<String> passes)
	{
		List<String> out = new ArrayList<>();
		for (String pass : passes)
		{
			out.add(pass.replaceAll(" total=\\d+", ""));
		}
		return out;
	}

	private static long total(String pass)
	{
		int at = pass.indexOf(" total=");
		return at < 0 ? 0 : Long.parseLong(pass.substring(at + 7));
	}
}
