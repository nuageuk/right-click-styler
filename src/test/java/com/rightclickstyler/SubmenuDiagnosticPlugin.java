package com.rightclickstyler;

import experiments.SubmenuRasterProbe;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.IdentityHashMap;
import javax.inject.Inject;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Rasterizer;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local-only live probe. It does not consume menu rendering or change entries. */
@PluginDescriptor(name = "Right Click Styler Submenu Diagnostic")
public class SubmenuDiagnosticPlugin extends Plugin
{
	private static final Logger LOG = LoggerFactory.getLogger(SubmenuDiagnosticPlugin.class);
	private static final String ENABLE_PROPERTY = "rightclickstyler.submenuProbe";
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static final long TRANSITION_INTERVAL_NS = 200_000_000L;

	@Inject
	private Client client;

	private boolean inProbe;
	private boolean disabledAfterFailure;
	private boolean hadSubmenu;
	private long events;
	private long samples;
	private long ambiguous;
	private long failures;
	private long scrollableSkips;
	private long reentrantEvents;
	private long totalNanos;
	private long totalDrawNanos;
	private long totalCopyNanos;
	private long totalCopiedPixels;
	private long totalAllocatedBytesMin;
	private long maxNanos;
	private long lastEventNanos;
	private long slowFrames;
	private long maxFrameGapNanos;
	private long lastReportNanos;
	private long lastTransitionLogNanos;
	private int lastSignature = Integer.MIN_VALUE;
	private long lastGcCount;
	private long lastGcMillis;

	@Override
	protected void startUp()
	{
		LOG.info("Submenu diagnostic started; probeEnabled={} (toggle with -PsubmenuProbe=false). No menu draws are consumed by this diagnostic.",
			Boolean.getBoolean(ENABLE_PROPERTY));
		lastReportNanos = System.nanoTime();
		lastGcCount = gcCount();
		lastGcMillis = gcMillis();
	}

	@Override
	protected void shutDown()
	{
		report();
		LOG.info("Submenu diagnostic stopped");
	}

	@Subscribe
	public void onBeforeMenuRender(BeforeMenuRender event)
	{
		if (inProbe)
		{
			reentrantEvents++;
			return;
		}
		if (!client.isMenuOpen())
		{
			return;
		}
		if (client.isMenuScrollable())
		{
			scrollableSkips++;
			return;
		}
		Menu root = client.getMenu();
		if (root == null || !containsSubmenu(root.getMenuEntries()))
		{
			return;
		}

		long now = System.nanoTime();
		events++;
		hadSubmenu = true;
		if (lastEventNanos != 0)
		{
			long gap = now - lastEventNanos;
			maxFrameGapNanos = Math.max(maxFrameGapNanos, gap);
			if (gap > 33_000_000L)
			{
				slowFrames++;
			}
		}
		lastEventNanos = now;

		boolean enabled = Boolean.getBoolean(ENABLE_PROPERTY) && !disabledAfterFailure;
		if (!enabled || event.isConsumed())
		{
			int signature = event.isConsumed() ? -2 : -1;
			transition(signature, now, root, event.isConsumed() ? "already consumed" : "baseline/no probe", null, 0);
			return;
		}

		inProbe = true;
		try
		{
			long probeStart = System.nanoTime();
			SubmenuRasterProbe.Result result = SubmenuRasterProbe.probe(client);
			long probeCallNanos = System.nanoTime() - probeStart;
			samples++;
			totalNanos += probeCallNanos;
			totalDrawNanos += result.drawNanos;
			totalCopyNanos += result.copyNanos;
			totalCopiedPixels += result.copiedPixels;
			totalAllocatedBytesMin += result.allocatedBytesMin;
			maxNanos = Math.max(maxNanos, probeCallNanos);
			if (!"sampled".equals(result.status))
			{
				ambiguous++;
			}
			if (!result.restored && result.copiedPixels > 0)
			{
				disabledAfterFailure = true;
				failures++;
				LOG.error("Submenu probe restore verification failed; further probing disabled");
			}
			int signature = 31 * (31 * result.status.hashCode() + Arrays.hashCode(result.activeChain))
				+ (client.isGpu() ? 1 : 0);
			transition(signature, now, root, result.status, result, probeCallNanos);
		}
		catch (RuntimeException e)
		{
			disabledAfterFailure = true;
			failures++;
			LOG.error("Submenu probe failed; further probing disabled. Native drawing remains enabled.", e);
		}
		finally
		{
			inProbe = false;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (hadSubmenu)
		{
			LOG.info("Native menu click after submenu observation: action={} id={}",
				event.getMenuAction(), event.getId());
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		long now = System.nanoTime();
		if (hadSubmenu && !client.isMenuOpen())
		{
			LOG.info("Observed submenu-containing menu closed");
			hadSubmenu = false;
			lastEventNanos = 0;
			lastSignature = Integer.MIN_VALUE;
		}
		if (now - lastReportNanos >= REPORT_INTERVAL_NS)
		{
			report();
			lastReportNanos = now;
		}
	}

	private void transition(int signature, long now, Menu root, String status,
		SubmenuRasterProbe.Result result, long probeCallNanos)
	{
		if (signature == lastSignature || now - lastTransitionLogNanos < TRANSITION_INTERVAL_NS)
		{
			return;
		}
		lastSignature = signature;
		lastTransitionLogNanos = now;
		Rasterizer raster = client.getRasterizer();
		BufferProvider provider = client.getBufferProvider();
		String geometry = geometry(root);
		LOG.info("Submenu state={} gpu={} canvas={}x{} raster={}x{} provider={}x{} samePixels={} geometry={} candidates={} chain={} witnesses={} changed={} rootPixels={}/{} probeCallMs={} sampleMs={} drawMs={} copyMs={} restored={} copiedPixels={} allocatedBytesMin={} reentrantEvents={}",
			status, client.isGpu(), client.getCanvasWidth(), client.getCanvasHeight(),
			raster == null ? -1 : raster.getWidth(), raster == null ? -1 : raster.getHeight(),
			provider == null ? -1 : provider.getWidth(), provider == null ? -1 : provider.getHeight(),
			raster != null && provider != null && raster.getPixels() == provider.getPixels(),
			geometry,
			result == null ? 0 : result.witnesses.length,
			result == null ? "[]" : Arrays.toString(result.activeChain),
			result == null ? "[]" : Arrays.toString(result.witnesses),
			result == null ? "[]" : Arrays.toString(result.changed),
			result == null ? 0 : result.rootChanged,
			result == null ? 0 : result.rootWitness,
			probeCallNanos / 1_000_000.0,
			result == null ? 0 : result.nanoseconds / 1_000_000.0,
			result == null ? 0 : result.drawNanos / 1_000_000.0,
			result == null ? 0 : result.copyNanos / 1_000_000.0,
			result != null && result.restored,
			result == null ? 0 : result.copiedPixels,
			result == null ? 0 : result.allocatedBytesMin,
			reentrantEvents);
	}

	private void report()
	{
		if (events == 0 && scrollableSkips == 0)
		{
			return;
		}
		long gcCount = gcCount();
		long gcMillis = gcMillis();
		LOG.info("Submenu summary events={} samples={} ambiguous={} failures={} scrollableSkips={} reentrant={} avgProbeCallMs={} avgNativeDrawMs={} avgCopyMs={} maxMs={} avgCopiedPixels={} avgAllocatedBytesMin={} gapsOver33ms={} maxGapMs={} gcCountDelta={} gcMillisDelta={}",
			events, samples, ambiguous, failures, scrollableSkips, reentrantEvents,
			samples == 0 ? 0 : totalNanos / (samples * 1_000_000.0),
			samples == 0 ? 0 : totalDrawNanos / (samples * 1_000_000.0),
			samples == 0 ? 0 : totalCopyNanos / (samples * 1_000_000.0),
			maxNanos / 1_000_000.0,
			samples == 0 ? 0 : totalCopiedPixels / samples,
			samples == 0 ? 0 : totalAllocatedBytesMin / samples,
			slowFrames, maxFrameGapNanos / 1_000_000.0,
			gcCount - lastGcCount, gcMillis - lastGcMillis);
		lastGcCount = gcCount;
		lastGcMillis = gcMillis;
		events = samples = ambiguous = failures = reentrantEvents = 0;
		scrollableSkips = 0;
		totalNanos = totalDrawNanos = totalCopyNanos = totalCopiedPixels = totalAllocatedBytesMin = 0;
		maxNanos = maxFrameGapNanos = slowFrames = 0;
	}

	private static boolean containsSubmenu(MenuEntry[] entries)
	{
		if (entries != null)
		{
			for (MenuEntry entry : entries)
			{
				if (entry != null && entry.getSubMenu() != null)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static String geometry(Menu root)
	{
		StringBuilder out = new StringBuilder(160);
		out.append("root(").append(root.getMenuX()).append(',').append(root.getMenuY())
			.append(',').append(root.getMenuWidth()).append(',').append(root.getMenuHeight()).append(')');
		appendChildren(root, out, new IdentityHashMap<>(), 0);
		return out.toString();
	}

	private static void appendChildren(Menu menu, StringBuilder out,
		IdentityHashMap<Menu, Boolean> seen, int depth)
	{
		if (depth > 8 || seen.put(menu, Boolean.TRUE) != null)
		{
			return;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null)
		{
			return;
		}
		for (int i = 0; i < entries.length; i++)
		{
			Menu child = entries[i] == null ? null : entries[i].getSubMenu();
			if (child != null)
			{
				out.append(" child[").append(depth).append(':').append(i).append("](")
					.append(child.getMenuX()).append(',').append(child.getMenuY()).append(',')
					.append(child.getMenuWidth()).append(',').append(child.getMenuHeight()).append(')');
				appendChildren(child, out, seen, depth + 1);
			}
		}
	}

	private static long gcCount()
	{
		long total = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
		{
			total += Math.max(0, bean.getCollectionCount());
		}
		return total;
	}

	private static long gcMillis()
	{
		long total = 0;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
		{
			total += Math.max(0, bean.getCollectionTime());
		}
		return total;
	}
}
