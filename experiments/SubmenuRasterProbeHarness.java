package experiments;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

/** Synthetic checks. They do not simulate RuneLite's rendering or input timing. */
public final class SubmenuRasterProbeHarness
{
	private static final int W = 80;
	private static final int H = 50;
	private static final Rectangle ROOT = new Rectangle(5, 5, 20, 30);
	private static final Rectangle CHILD_A = new Rectangle(25, 8, 18, 18);
	private static final Rectangle CHILD_B = new Rectangle(25, 28, 18, 18);
	private static final Rectangle GRANDCHILD = new Rectangle(43, 8, 18, 18);

	private SubmenuRasterProbeHarness()
	{
	}

	public static void main(String[] args)
	{
		check("active first child", Arrays.asList(CHILD_A, CHILD_B),
			Arrays.asList(ROOT, CHILD_A), new boolean[]{true, false}, new int[]{0});
		check("active second child", Arrays.asList(CHILD_A, CHILD_B),
			Arrays.asList(ROOT, CHILD_B), new boolean[]{false, true}, new int[]{1});
		check("delayed or closed child", Arrays.asList(CHILD_A, CHILD_B),
			Arrays.asList(ROOT), new boolean[]{false, false}, new int[0]);
		int[] nestedPixels = new int[W * H];
		SubmenuRasterProbe.Result nested = SubmenuRasterProbe.probePixels(nestedPixels, W, H,
			ROOT, Arrays.asList(CHILD_A, CHILD_B, GRANDCHILD), new int[]{-1, -1, 0},
			() -> draw(nestedPixels, W, H, Arrays.asList(ROOT, CHILD_A, GRANDCHILD)));
		if (!"sampled".equals(nested.status) || !Arrays.equals(nested.activeChain, new int[]{0, 2}))
		{
			throw new AssertionError("nested chain: " + Arrays.toString(nested.activeChain));
		}
		System.out.println("PASS nested child chain " + Arrays.toString(nested.activeChain));
		check("canvas edge", Arrays.asList(new Rectangle(70, 4, 20, 20)),
			Arrays.asList(ROOT, new Rectangle(70, 4, 20, 20)), new boolean[]{true}, new int[]{0});

		int[] pixels = new int[W * H];
		Rectangle enclosed = new Rectangle(10, 10, 10, 10);
		SubmenuRasterProbe.Result overlap = SubmenuRasterProbe.probePixels(pixels, W, H,
			ROOT, Arrays.asList(enclosed), () -> draw(pixels, W, H, Arrays.asList(ROOT, enclosed)));
		if (overlap.witnesses[0] != 0 || overlap.changed[0] != 0
			|| !"ambiguous geometry".equals(overlap.status))
		{
			throw new AssertionError("enclosed child must be ambiguous");
		}
		System.out.println("PASS fully overlapping child: ambiguous");

		Rectangle same = new Rectangle(CHILD_A);
		SubmenuRasterProbe.Result siblings = SubmenuRasterProbe.probePixels(pixels, W, H,
			ROOT, Arrays.asList(CHILD_A, same), () -> draw(pixels, W, H, Arrays.asList(ROOT, CHILD_A)));
		if (siblings.witnesses[0] != 0 || siblings.witnesses[1] != 0
			|| !"ambiguous geometry".equals(siblings.status))
		{
			throw new AssertionError("identical siblings must be ambiguous");
		}
		System.out.println("PASS identical sibling bounds: ambiguous");
		int[] baseline = new int[W * H];
		int[] mutated = new int[W * H];
		draw(baseline, W, H, Arrays.asList(ROOT, enclosed));
		draw(mutated, W, H, Arrays.asList(ROOT, enclosed));
		mutated[12 * W + 12] = 0xFF123456;
		if (SubmenuMutationProbe.difference(baseline, mutated, W, H, enclosed) != 1)
		{
			throw new AssertionError("active enclosed child mutation not detected");
		}
		if (SubmenuMutationProbe.difference(baseline, baseline, W, H, enclosed) != 0)
		{
			throw new AssertionError("inactive enclosed child mutation false positive");
		}
		System.out.println("PASS differential comparison within overlapping child");
		int[] failurePixels = new int[W * H];
		Arrays.fill(failurePixels, 0xFF223344);
		int[] failureBefore = failurePixels.clone();
		try
		{
			SubmenuRasterProbe.probePixels(failurePixels, W, H, ROOT,
				Arrays.asList(CHILD_A), () ->
				{
					draw(failurePixels, W, H, Arrays.asList(ROOT, CHILD_A));
					throw new IllegalStateException("synthetic draw failure");
				});
			throw new AssertionError("expected draw failure");
		}
		catch (IllegalStateException expected)
		{
			if (!Arrays.equals(failurePixels, failureBefore))
			{
				throw new AssertionError("buffer not restored after draw failure");
			}
		}
		System.out.println("PASS buffer restored after draw exception");

		benchmark(800, 600);
		benchmark(1920, 1080);
	}

	private static void check(String name, List<Rectangle> candidates,
		List<Rectangle> drawn, boolean[] expected, int[] expectedChain)
	{
		int[] pixels = new int[W * H];
		Arrays.fill(pixels, 0xFF334455);
		int[] original = pixels.clone();
		SubmenuRasterProbe.Result result = SubmenuRasterProbe.probePixels(pixels, W, H,
			ROOT, candidates, () -> draw(pixels, W, H, drawn));
		for (int i = 0; i < expected.length; i++)
		{
			if ((result.changed[i] > 0) != expected[i])
			{
				throw new AssertionError(name + " candidate " + i + ": " + result.changed[i]);
			}
		}
		if (!Arrays.equals(pixels, original))
		{
			throw new AssertionError(name + ": buffer not restored");
		}
		if (!"sampled".equals(result.status) || !Arrays.equals(result.activeChain, expectedChain))
		{
			throw new AssertionError(name + " chain " + Arrays.toString(result.activeChain)
				+ " status " + result.status);
		}
		System.out.println("PASS " + name + " " + Arrays.toString(result.changed));
	}

	private static void draw(int[] pixels, int width, int height, List<Rectangle> rectangles)
	{
		for (Rectangle rectangle : rectangles)
		{
			Rectangle clipped = rectangle.intersection(new Rectangle(0, 0, width, height));
			for (int y = clipped.y; y < clipped.y + clipped.height; y++)
			{
				for (int x = clipped.x; x < clipped.x + clipped.width; x++)
				{
					pixels[y * width + x] = 0xFF675944;
				}
			}
		}
	}

	private static void benchmark(int width, int height)
	{
		int[] pixels = new int[width * height];
		Rectangle root = new Rectangle(50, 50, 200, 280);
		Rectangle child = new Rectangle(250, 70, 180, 260);
		List<Rectangle> candidates = Arrays.asList(child);
		List<Rectangle> drawn = Arrays.asList(root, child);
		for (int i = 0; i < 30; i++)
		{
			SubmenuRasterProbe.probePixels(pixels, width, height, root, candidates,
				() -> draw(pixels, width, height, drawn));
		}
		long baselineStart = System.nanoTime();
		for (int i = 0; i < 100; i++)
		{
			draw(pixels, width, height, drawn);
		}
		long baseline = System.nanoTime() - baselineStart;
		long total = 0;
		long nativeDraw = 0;
		long copy = 0;
		long allocatedMin = 0;
		for (int i = 0; i < 100; i++)
		{
			long start = System.nanoTime();
			SubmenuRasterProbe.Result result = SubmenuRasterProbe.probePixels(pixels, width, height, root, candidates,
				() -> draw(pixels, width, height, drawn));
			total += System.nanoTime() - start;
			nativeDraw += result.drawNanos;
			copy += result.copyNanos;
			allocatedMin += result.allocatedBytesMin;
		}
		System.out.printf("SYNTHETIC %dx%d baseline draw %.3f ms, probe %.3f ms, inner draw %.3f ms, copy %.3f ms, minimum allocation %.1f MiB/sample%n",
			width, height, baseline / 100_000_000.0, total / 100_000_000.0,
			nativeDraw / 100_000_000.0, copy / 100_000_000.0,
			allocatedMin / 100.0 / (1024 * 1024));

		ReusableRegionSnapshot region = new ReusableRegionSnapshot(width, height, drawn);
		for (int i = 0; i < 30; i++)
		{
			region.capture(pixels);
			region.restore(pixels);
		}
		long regionStart = System.nanoTime();
		for (int i = 0; i < 100; i++)
		{
			region.capture(pixels);
			draw(pixels, width, height, drawn);
			region.restore(pixels);
		}
		long regionTotal = System.nanoTime() - regionStart;
		System.out.printf("SYNTHETIC %dx%d reusable region copy+draw+restore %.3f ms, copied %.1f MiB/sample, steady-state snapshot allocations 0%n",
			width, height, regionTotal / 100_000_000.0,
			region.copiedPixels() * 4.0 / (1024 * 1024));
	}
}
