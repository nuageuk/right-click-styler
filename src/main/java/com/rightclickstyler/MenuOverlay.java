package com.rightclickstyler;

import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
class MenuOverlay extends Overlay
{
	private static final int NO_ITEM = -1;
	private static final boolean SUBMENU_TRACE = Boolean.getBoolean("rightclickstyler.submenuTrace");
	// Diagnostic A/B only. Both paths require the separate submenuPrototype opt-in.
	private static final boolean HARDENED_LIFECYCLE =
		!"baseline".equals(System.getProperty("rightclickstyler.submenuLifecycle", "hardened"));

	static boolean hardenedLifecycle()
	{
		return HARDENED_LIFECYCLE;
	}

	private enum PrototypeOutcome
	{
		READY_ROOT_ONLY,
		READY_WITH_CHILD,
		READY_WITH_NATIVE_DETACHED_CHILD,
		FALLBACK_AMBIGUOUS,
		FALLBACK_INVALID_BOUNDS,
		FALLBACK_BUFFER_MISMATCH,
		FALLBACK_NATIVE_DRAW_MISSED,
		FALLBACK_RESTORE_FAILURE,
		FALLBACK_GEOMETRY_CHANGED,
		FALLBACK_TREE_DEPTH,
		FALLBACK_TREE_COUNT,
		FALLBACK_TREE_CYCLE,
		FALLBACK_COMPLEXITY,
		FALLBACK_RENDER_EXCEPTION,
		FALLBACK_PROBE_EXCEPTION,
		FALLBACK_SCROLLABLE,
		FALLBACK_ALREADY_CONSUMED,
		FALLBACK_TRANSIENT,
		FALLBACK_OTHER
	}

	private final Client client;
	private final RightClickStylerConfig config;
	private final ThemeManager themeManager;
	private final MenuRenderer renderer = new MenuRenderer();
	private final SubmenuPrototypeProbe prototypeProbe = new SubmenuPrototypeProbe();
	private final List<MenuRenderer.Row> rows = new ArrayList<>();
	private final List<MenuRenderer.Row> rowPool = new ArrayList<>();
	private final Rectangle bounds = new Rectangle();
	private final List<RowBuffer> prototypeRows = new ArrayList<>();
	private BufferedImage prototypeImage;
	private int preparedX;
	private int preparedY;
	private Menu preparedRoot;
	private PreparedMenuState preparedState;
	private SubmenuTree preparedTree;
	private int[] preparedChain;
	private ScrollableMenuViewport preparedViewport;
	private Rectangle preparedRegion;
	private Theme preparedTheme;
	private int preparedLocalLevel;
	private boolean preparedScrollable;
	private int preparedScroll;
	private boolean prepared;
	private long prototypeCalls;
	private long prototypeReady;
	private long prototypeProbeNanos;
	private long prototypeDrawNanos;
	private long prototypeRenderNanos;
	private long prototypeTotalNanos;
	private long prototypeMaxNanos;
	private long prototypeLastReport;
	private long prototypeLastTransition;
	private long prototypeLastFailureLog;
	private long prototypeLastSlowProbeLog;
	private int prototypeSignature = Integer.MIN_VALUE;
	private final long[] prototypeOutcomes = new long[PrototypeOutcome.values().length];
	private long prototypeBlits;
	private long prototypeClosedDrops;
	private long prototypeChangedDrops;
	private long prototypeReplacedBeforeBlit;
	private long prototypeReentrantEvents;
	private long prototypeBlitFailures;
	private boolean prototypeBlitFailed;

	/**
	 * Set after the first render failure. The plugin consumes {@code BeforeMenuRender}, so a render
	 * that throws would leave the menu invisible; once this is set we stop consuming and the vanilla
	 * menu is drawn for the rest of the session. Volatile because the plugin resets it from startUp.
	 */
	private volatile boolean failed;

	@Inject
	private MenuOverlay(Client client, RightClickStylerConfig config, ThemeManager themeManager)
	{
		this.client = client;
		this.config = config;
		this.themeManager = themeManager;
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPosition(OverlayPosition.DYNAMIC);
		if (SUBMENU_TRACE)
		{
			log.info("Submenu prototype diagnostic lifecycle={}",
				HARDENED_LIFECYCLE ? "hardened" : "baseline");
		}
	}

	void resetFailure()
	{
		failed = false;
		prototypeBlitFailed = false;
		clearPrepared();
	}

	synchronized void discardPreparedAtEventStart()
	{
		if (prepared)
		{
			prototypeReplacedBeforeBlit++;
		}
		clearPrepared();
	}

	synchronized void recordAlreadyConsumedSubmenu()
	{
		prototypeCalls++;
		recordOutcome(PrototypeOutcome.FALLBACK_ALREADY_CONSUMED);
		prototypeTransition("FALLBACK_ALREADY_CONSUMED", null, null, 0);
	}

	synchronized void recordReentrantEvent()
	{
		prototypeReentrantEvents++;
	}

	private void recordOutcome(PrototypeOutcome outcome)
	{
		prototypeOutcomes[outcome.ordinal()]++;
	}

	/** Prepares submenu menus and scrollable roots only under the explicit prototype flag. */
	synchronized boolean prepareSubmenuFrame(Menu root, MenuEntry[] rootEntries)
	{
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		preparedTree = null;
		preparedChain = null;
		preparedViewport = null;
		preparedRegion = null;
		preparedTheme = null;
		prototypeCalls++;
		if (!client.isMenuOpen())
		{
			recordOutcome(PrototypeOutcome.FALLBACK_TRANSIENT);
			prototypeTransition(PrototypeOutcome.FALLBACK_TRANSIENT.name(), null, null, 0);
			return false;
		}
		boolean scrollable = client.isMenuScrollable();
		int scroll = scrollable ? client.getMenuScroll() : 0;
		Point probeMouse = HARDENED_LIFECYCLE ? client.getMouseCanvasPosition() : null;
		long start = System.nanoTime();
		if (prototypeBlitFailed)
		{
			recordOutcome(PrototypeOutcome.FALLBACK_RENDER_EXCEPTION);
			prototypeTransition("FALLBACK_RENDER_EXCEPTION: final blit disabled prototype", null, null, 0);
			return false;
		}
		boolean preparingTheme = false;
		SubmenuPrototypeProbe.Result timingResult = null;
		long timingProbeCallNanos = 0;
		try
		{
			int canvasWidth = client.getCanvasWidth();
			int canvasHeight = client.getCanvasHeight();
			if (canvasWidth <= 0 || canvasHeight <= 0
				|| (long) canvasWidth * canvasHeight > 8_388_608L)
			{
				recordOutcome(PrototypeOutcome.FALLBACK_INVALID_BOUNDS);
				prototypeTransition("FALLBACK_INVALID_BOUNDS: invalid canvas dimensions", null, null, 0);
				return false;
			}
			ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
				new Rectangle(root.getMenuX(), root.getMenuY(), root.getMenuWidth(), root.getMenuHeight()),
				rootEntries.length,
				canvasWidth, canvasHeight, scrollable, scroll);
			if (viewport == null)
			{
				recordOutcome(PrototypeOutcome.FALLBACK_SCROLLABLE);
				prototypeTransition("FALLBACK_SCROLLABLE: invalid root scroll viewport", null, null, 0);
				return false;
			}
			SubmenuTree tree = SubmenuTree.capture(root, rootEntries,
				scrollable ? viewport.firstRow : 0);
			long probeStart = System.nanoTime();
			SubmenuPrototypeProbe.Result result = prototypeProbe.probe(client, tree);
			long nativeDrawNanos = result.drawNanos;
			if (result.state == SubmenuPrototypeProbe.State.GEOMETRY_CHANGED)
			{
				// Native drawing can update even a previously nonzero child rectangle.
				// Re-sample once against the current positions.
				tree = SubmenuTree.capture(root, root.getMenuEntries(),
					scrollable ? viewport.firstRow : 0);
				result = prototypeProbe.probe(client, tree);
				nativeDrawNanos += result.drawNanos;
				viewport = ScrollableMenuViewport.capture(tree.nodes.get(0).bounds,
					tree.nodes.get(0).entries.length, canvasWidth, canvasHeight,
					scrollable, scroll);
			}
			long probeCallNanos = System.nanoTime() - probeStart;
			timingResult = result;
			timingProbeCallNanos = probeCallNanos;
			prototypeProbeNanos += probeCallNanos;
			prototypeDrawNanos += nativeDrawNanos;
			if (viewport == null || client.isMenuScrollable() != scrollable
				|| (scrollable && client.getMenuScroll() != scroll))
			{
				recordOutcome(PrototypeOutcome.FALLBACK_SCROLLABLE);
				prototypeTransition("FALLBACK_SCROLLABLE: scroll state changed during probe",
					result, tree, probeCallNanos);
				return false;
			}
			if (result.state != SubmenuPrototypeProbe.State.NO_CHILD
				&& result.state != SubmenuPrototypeProbe.State.ACTIVE)
			{
				PrototypeOutcome outcome = failureOutcome(result);
				recordOutcome(outcome);
				prototypeTransition(outcome + ": " + result.reason, result, tree, probeCallNanos);
				return false;
			}
			ScrollChildValidation childValidation = scrollable
				? validateScrolledChild(tree, result.activeChain, viewport, canvasHeight, result)
				: ScrollChildValidation.ATTACHED;
			if (childValidation.reason != null)
			{
				recordOutcome(PrototypeOutcome.FALLBACK_SCROLLABLE);
				prototypeTransition("FALLBACK_SCROLLABLE: " + childValidation.reason,
					result, tree, probeCallNanos);
				return false;
			}
			Rectangle region = new Rectangle(tree.nodes.get(0).bounds);
			for (int candidate : result.activeChain)
			{
				region.add(tree.nodes.get(candidate + 1).bounds);
			}
			region.grow(3, 3);
			region = region.intersection(new Rectangle(0, 0, canvasWidth, canvasHeight));
			if (region.width <= 0 || region.height <= 0)
			{
				recordOutcome(PrototypeOutcome.FALLBACK_INVALID_BOUNDS);
				prototypeTransition("FALLBACK_INVALID_BOUNDS: menu outside canvas", result, tree, probeCallNanos);
				return false;
			}
			if (prototypeImage == null || prototypeImage.getWidth() != region.width
				|| prototypeImage.getHeight() != region.height)
			{
				prototypeImage = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
			}
			Point renderMouse = client.getMouseCanvasPosition();
			PreparedMenuState state = null;
			if (HARDENED_LIFECYCLE)
			{
				state = PreparedMenuState.capture(tree, result.activeChain,
					renderMouse, viewport);
				String mismatch = state.mismatchReason(client.getMenu(), renderMouse);
				if (!state.allowsMovement(probeMouse, renderMouse) || mismatch != null)
				{
					recordOutcome(PrototypeOutcome.FALLBACK_TRANSIENT);
					prototypeTransition("FALLBACK_TRANSIENT: " + (mismatch == null
						? state.movementReason(probeMouse, renderMouse) : mismatch)
						+ " probeMouse=" + probeMouse + " validationMouse=" + renderMouse,
						result, tree, probeCallNanos);
					return false;
				}
			}
			preparingTheme = true;
			Theme theme = themeManager.effectiveTheme();
			Player player = client.getLocalPlayer();
			int localLevel = player == null ? 0 : player.getCombatLevel();
			if (!HARDENED_LIFECYCLE)
			{
				long renderStart = System.nanoTime();
				renderPrototypeImage(tree, result.activeChain, viewport, region,
					renderMouse, scroll, theme, localLevel);
				prototypeRenderNanos += System.nanoTime() - renderStart;
			}
			else
			{
				for (int attempt = 0; ; attempt++)
				{
					long renderStart = System.nanoTime();
					renderPrototypeImage(tree, result.activeChain, viewport, region,
						renderMouse, scroll, theme, localLevel);
					prototypeRenderNanos += System.nanoTime() - renderStart;
					Point currentMouse = null;
					String mismatch;
					if (!client.isMenuOpen())
					{
						mismatch = "menu closed";
					}
					else if (client.isMenuScrollable() != scrollable
						|| (scrollable && client.getMenuScroll() != scroll))
					{
						mismatch = "scroll state";
					}
					else
					{
						currentMouse = client.getMouseCanvasPosition();
						mismatch = state.mismatchReason(client.getMenu(), currentMouse);
						if (mismatch != null && mismatch.startsWith("mouse hover ")
							&& attempt < 2 && state.allowsMovement(renderMouse, currentMouse))
						{
							renderMouse = currentMouse;
							state.setRenderedMouse(renderMouse);
							continue;
						}
					}
					if (mismatch != null)
					{
						recordOutcome(PrototypeOutcome.FALLBACK_TRANSIENT);
						prototypeTransition("FALLBACK_TRANSIENT: " + mismatch
							+ " renderedMouse=" + renderMouse + " validationMouse=" + currentMouse,
							result, tree, probeCallNanos);
						return false;
					}
					break;
				}
			}
			preparedX = region.x;
			preparedY = region.y;
			preparedRoot = tree.root;
			if (HARDENED_LIFECYCLE)
			{
				preparedState = state;
				preparedTree = tree;
				preparedChain = result.activeChain;
				preparedViewport = viewport;
				preparedRegion = region;
				preparedTheme = theme;
				preparedLocalLevel = localLevel;
			}
			preparedScrollable = scrollable;
			preparedScroll = scroll;
			prepared = true;
			prototypeReady++;
			PrototypeOutcome readyOutcome = result.state == SubmenuPrototypeProbe.State.NO_CHILD
				? PrototypeOutcome.READY_ROOT_ONLY
				: childValidation.detached ? PrototypeOutcome.READY_WITH_NATIVE_DETACHED_CHILD
				: PrototypeOutcome.READY_WITH_CHILD;
			recordOutcome(readyOutcome);
			prototypeTransition(readyOutcome.name(), result, tree, probeCallNanos);
			return true;
		}
		catch (IllegalArgumentException e)
		{
			clearPrepared();
			PrototypeOutcome outcome = preparingTheme ? PrototypeOutcome.FALLBACK_RENDER_EXCEPTION
				: treeFailureOutcome(e.getMessage());
			recordOutcome(outcome);
			int rootSubmenuRefs = -1;
			if (rootEntries != null)
			{
				try
				{
					rootSubmenuRefs = 0;
					for (MenuEntry entry : rootEntries)
					{
						if (entry != null && entry.getSubMenu() != null)
						{
							rootSubmenuRefs++;
						}
					}
				}
				catch (RuntimeException ignored)
				{
					rootSubmenuRefs = -1;
				}
			}
			prototypeTransition(outcome + ": " + e.getMessage() + " rootEntries="
				+ (rootEntries == null ? 0 : rootEntries.length)
				+ " rootSubmenuRefs=" + rootSubmenuRefs, null, null, 0);
			return false;
		}
		catch (RuntimeException e)
		{
			clearPrepared();
			PrototypeOutcome outcome = preparingTheme
				? PrototypeOutcome.FALLBACK_RENDER_EXCEPTION : PrototypeOutcome.FALLBACK_PROBE_EXCEPTION;
			recordOutcome(outcome);
			prototypeTransition(outcome + ": " + e.getClass().getSimpleName(), null, null, 0);
			long now = System.nanoTime();
			if (now - prototypeLastFailureLog >= 5_000_000_000L)
			{
				prototypeLastFailureLog = now;
				log.warn("Submenu prototype preparation failed; native menu retained", e);
			}
			return false;
		}
		finally
		{
			long elapsed = System.nanoTime() - start;
			prototypeTotalNanos += elapsed;
			prototypeMaxNanos = Math.max(prototypeMaxNanos, elapsed);
			long now = System.nanoTime();
			if (elapsed >= 10_000_000L
				&& now - prototypeLastSlowProbeLog >= 3_000_000_000L)
			{
				prototypeLastSlowProbeLog = now;
				log.info("Submenu prototype slow totalMs={} probeCallMs={} scroll={} rootEntries={} passes={} prepMs={} copyMs={} markerMs={} compareMs={} restoreMs={} decisionMs={} nativeDrawMs={} allocatedBytesMin={}",
					elapsed / 1_000_000.0, timingProbeCallNanos / 1_000_000.0,
					scroll, rootEntries == null ? 0 : rootEntries.length,
					timingResult == null ? 0 : timingResult.drawPasses,
					timingResult == null ? 0 : timingResult.preparationNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.copyNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.markerNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.comparisonNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.restoreNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.decisionNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.drawNanos / 1_000_000.0,
					timingResult == null ? 0 : timingResult.allocatedBytesMin);
			}
		}
	}

	private void renderPrototypeImage(SubmenuTree tree, int[] activeChain,
		ScrollableMenuViewport viewport, Rectangle region, Point mouse, int scroll,
		Theme theme, int localLevel)
	{
		Graphics2D g = prototypeImage.createGraphics();
		try
		{
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, region.width, region.height);
			g.setComposite(AlphaComposite.SrcOver);
			g.translate(-region.x, -region.y);
			for (int depth = 0; depth <= activeChain.length; depth++)
			{
			SubmenuTree.Node node = tree.nodes.get(depth == 0 ? 0 : activeChain[depth - 1] + 1);
			RowBuffer buffer = prototypeRowBuffer(depth);
			fillRows(node.entries, node.childFlags, node.childOpensLeft, buffer.rows, buffer.pool);
			int hovered = depth == 0
				? viewport.hoverIndex(mouse)
				: hoverIndex(node.bounds, buffer.rows.size(), mouse);
			renderer.render(g, client, theme, config, buffer.rows, node.bounds,
				hovered, localLevel, node.header, depth == 0 ? scroll : 0);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	private static PrototypeOutcome treeFailureOutcome(String reason)
	{
		if (reason == null)
		{
			return PrototypeOutcome.FALLBACK_OTHER;
		}
		if (reason.startsWith("menu depth limit"))
		{
			return PrototypeOutcome.FALLBACK_TREE_DEPTH;
		}
		if (reason.startsWith("menu count limit")
			|| reason.startsWith("menu total entry count limit")
			|| reason.startsWith("invalid menu entry count"))
		{
			return PrototypeOutcome.FALLBACK_TREE_COUNT;
		}
		if (reason.startsWith("menu identity cycle"))
		{
			return PrototypeOutcome.FALLBACK_TREE_CYCLE;
		}
		if (reason.contains("bounds"))
		{
			return PrototypeOutcome.FALLBACK_INVALID_BOUNDS;
		}
		return PrototypeOutcome.FALLBACK_OTHER;
	}

	static String unsafeScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight)
	{
		return validateScrolledChild(tree, chain, rootViewport, canvasHeight, null).reason;
	}

	static ScrollChildValidation validateScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight,
		SubmenuPrototypeProbe.Result proof)
	{
		boolean detached = false;
		for (int candidate : chain)
		{
			SubmenuTree.Node child = tree.nodes.get(candidate + 1);
			SubmenuTree.Node parent = tree.nodes.get(child.parentNode);
			if (child.entries.length == 0 || child.bounds.height > canvasHeight
				|| (long) child.entries.length * MenuRenderer.ROW_HEIGHT
					+ MenuRenderer.HEADER_HEIGHT > canvasHeight)
			{
				return ScrollChildValidation.reject("active child may need an unavailable submenu scroll offset");
			}
			int row = parent.entries.length - 1 - child.parentEntryIndex;
			int rowTop;
			if (child.parentNode == 0)
			{
				if (!rootViewport.containsRow(row))
				{
					return ScrollChildValidation.reject("active child parent row is outside the root viewport"
						+ " row=" + row + " scroll=" + rootViewport.scroll);
				}
				rowTop = rootViewport.rowTop(row);
			}
			else
			{
				rowTop = parent.bounds.y + MenuRenderer.HEADER_HEIGHT
					+ row * MenuRenderer.ROW_HEIGHT;
			}
			if (rowTop >= canvasHeight || rowTop + MenuRenderer.ROW_HEIGHT <= 0)
			{
				return ScrollChildValidation.reject("active child parent row is outside the canvas"
					+ " row=" + row + " rowTop=" + rowTop);
			}
			if (child.bounds.y >= rowTop + MenuRenderer.ROW_HEIGHT
				|| child.bounds.y + child.bounds.height <= rowTop)
			{
				// Native drawing can retain a child's old position for a scroll
				// transition. Theme it at that exact position only when the same
				// probe proves this child was actually drawn there.
				boolean onProofChain = false;
				if (proof != null)
				{
					for (int proved : proof.activeChain)
					{
						onProofChain |= proved == candidate;
					}
				}
				boolean nativeDrawn = proof != null && onProofChain
					&& proof.state == SubmenuPrototypeProbe.State.ACTIVE
					&& proof.restored && candidate < proof.changed.length
					&& (proof.changed[candidate] > 0
						|| proof.footprintMatches == 1 && proof.footprintChangedPixels > 0);
				if (!nativeDrawn)
				{
					return ScrollChildValidation.reject("active child bounds are detached from its visible parent row"
						+ " row=" + row + " rowTop=" + rowTop + " child=" + child.bounds
						+ " exclusive=" + (proof == null || candidate >= proof.changed.length
							? 0 : proof.changed[candidate])
						+ " footprintMatches=" + (proof == null ? 0 : proof.footprintMatches));
				}
				detached = true;
			}
		}
		return detached ? ScrollChildValidation.DETACHED : ScrollChildValidation.ATTACHED;
	}

	static final class ScrollChildValidation
	{
		static final ScrollChildValidation ATTACHED = new ScrollChildValidation(null, false);
		static final ScrollChildValidation DETACHED = new ScrollChildValidation(null, true);
		final String reason;
		final boolean detached;

		private ScrollChildValidation(String reason, boolean detached)
		{
			this.reason = reason;
			this.detached = detached;
		}

		static ScrollChildValidation reject(String reason)
		{
			return new ScrollChildValidation(reason, false);
		}
	}

	private static PrototypeOutcome failureOutcome(SubmenuPrototypeProbe.Result result)
	{
		if (result.state == SubmenuPrototypeProbe.State.AMBIGUOUS)
		{
			return PrototypeOutcome.FALLBACK_AMBIGUOUS;
		}
		if (result.reason.startsWith("candidate pixel area limit"))
		{
			return PrototypeOutcome.FALLBACK_COMPLEXITY;
		}
		if ("framebuffer restoration failed".equals(result.reason))
		{
			return PrototypeOutcome.FALLBACK_RESTORE_FAILURE;
		}
		if ("native draw missed buffer".equals(result.reason))
		{
			return PrototypeOutcome.FALLBACK_NATIVE_DRAW_MISSED;
		}
		if (result.reason.contains("raster/provider")
			|| result.reason.contains("buffer identity")
			|| "invalid framebuffer".equals(result.reason))
		{
			return PrototypeOutcome.FALLBACK_BUFFER_MISMATCH;
		}
		if (result.state == SubmenuPrototypeProbe.State.GEOMETRY_CHANGED)
		{
			return PrototypeOutcome.FALLBACK_GEOMETRY_CHANGED;
		}
		if (result.reason.contains("geometry"))
		{
			return PrototypeOutcome.FALLBACK_INVALID_BOUNDS;
		}
		return PrototypeOutcome.FALLBACK_OTHER;
	}

	synchronized void clearPrepared()
	{
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		preparedTree = null;
		preparedChain = null;
		preparedViewport = null;
		preparedRegion = null;
		preparedTheme = null;
		preparedScrollable = false;
		preparedScroll = 0;
	}

	synchronized void reportPrototype()
	{
		long now = System.nanoTime();
		if (now - prototypeLastReport < 5_000_000_000L || prototypeCalls == 0)
		{
			return;
		}
		StringBuilder reasons = new StringBuilder();
		for (PrototypeOutcome outcome : PrototypeOutcome.values())
		{
			if (reasons.length() > 0)
			{
				reasons.append(' ');
			}
			reasons.append(outcome).append('=').append(prototypeOutcomes[outcome.ordinal()]);
		}
		log.info("Submenu prototype summary calls={} ready={} fallback={} {} blits={} blitFailures={} closedDrops={} changedRootDrops={} replacedBeforeBlit={} reentrantEvents={} avgTotalMs={} avgProbeMs={} avgNativeDrawMs={} avgThemeMs={} maxTotalMs={}",
			prototypeCalls, prototypeReady, prototypeCalls - prototypeReady,
			reasons, prototypeBlits, prototypeBlitFailures, prototypeClosedDrops, prototypeChangedDrops,
			prototypeReplacedBeforeBlit, prototypeReentrantEvents,
			prototypeTotalNanos / (prototypeCalls * 1_000_000.0),
			prototypeProbeNanos / (prototypeCalls * 1_000_000.0),
			prototypeDrawNanos / (prototypeCalls * 1_000_000.0),
			prototypeRenderNanos / (prototypeCalls * 1_000_000.0),
			prototypeMaxNanos / 1_000_000.0);
		prototypeCalls = prototypeReady = prototypeProbeNanos = prototypeDrawNanos = 0;
		prototypeRenderNanos = prototypeTotalNanos = prototypeMaxNanos = 0;
		prototypeBlits = prototypeBlitFailures = prototypeClosedDrops = prototypeChangedDrops = 0;
		prototypeReplacedBeforeBlit = prototypeReentrantEvents = 0;
		Arrays.fill(prototypeOutcomes, 0);
		prototypeLastReport = now;
	}

	private void prototypeTransition(String state, SubmenuPrototypeProbe.Result result,
		SubmenuTree tree, long probeCallNanos)
	{
		int signature = 31 * state.hashCode() + (result == null ? 0 : Arrays.hashCode(result.activeChain));
		int rootTextHash = 0;
		if (SUBMENU_TRACE && tree != null)
		{
			signature = 31 * signature + (client.isMenuScrollable() ? 1 : 0);
			signature = 31 * signature + client.getMenuScroll();
			signature = 31 * signature + System.identityHashCode(tree.root);
			signature = 31 * signature + tree.nodes.get(0).bounds.hashCode();
			signature = 31 * signature + tree.candidateBounds.hashCode();
			if (result != null)
			{
				signature = 31 * signature + result.footprintMatches;
				signature = 31 * signature + (result.footprintBounds == null
					? 0 : result.footprintBounds.hashCode());
			}
			for (MenuEntry entry : tree.nodes.get(0).entries)
			{
				if (entry != null)
				{
					rootTextHash = 31 * rootTextHash + String.valueOf(entry.getOption()).hashCode();
					rootTextHash = 31 * rootTextHash + String.valueOf(entry.getTarget()).hashCode();
				}
			}
			signature = 31 * signature + rootTextHash;
		}
		long now = System.nanoTime();
		if (signature == prototypeSignature
			|| (!SUBMENU_TRACE && now - prototypeLastTransition < 200_000_000L))
		{
			return;
		}
		prototypeSignature = signature;
		prototypeLastTransition = now;
		List<String> activeParents = new ArrayList<>();
		if (tree != null && result != null)
		{
			for (int candidate : result.activeChain)
			{
				SubmenuTree.Node child = tree.nodes.get(candidate + 1);
				SubmenuTree.Node parent = tree.nodes.get(child.parentNode);
				int row = parent.entries.length - 1 - child.parentEntryIndex;
				activeParents.add("candidate=" + candidate + " entry=" + child.parentEntryIndex
					+ " row=" + row + " rowTop=" + (parent.bounds.y
						+ MenuRenderer.HEADER_HEIGHT + (row - (child.parentNode == 0
							? client.getMenuScroll() : 0)) * MenuRenderer.ROW_HEIGHT));
			}
		}
		log.info("Submenu prototype state={} rootId={} rootTextHash={} root={} scrollable={} scroll={} canvas={}x{} mouse={} candidates={} bounds={} parents={} chain={} activeParents={} witnesses={} changed={} sharedWitnesses={} sharedChanged={} footprint={} footprintPixels={} footprintMatches={} restored={} probeCallMs={} nativeDrawMs={} nativeDrawPasses={} copiedPixels={} allocatedBytesMin={}",
			state,
			tree == null ? 0 : System.identityHashCode(tree.root),
			rootTextHash,
			tree == null ? "unknown" : tree.nodes.get(0).bounds,
			client.isMenuScrollable(), client.getMenuScroll(),
			client.getCanvasWidth(), client.getCanvasHeight(), client.getMouseCanvasPosition(),
			tree == null ? 0 : tree.candidateBounds.size(),
			tree == null ? "[]" : tree.candidateBounds,
			tree == null ? "[]" : tree.candidateParents,
			result == null ? "[]" : Arrays.toString(result.activeChain),
			activeParents,
			result == null ? "[]" : Arrays.toString(result.witnesses),
			result == null ? "[]" : Arrays.toString(result.changed),
			result == null ? "[]" : Arrays.toString(result.sharedWitnesses),
			result == null ? "[]" : Arrays.toString(result.sharedChanged),
			result == null ? null : result.footprintBounds,
			result == null ? 0 : result.footprintChangedPixels,
			result == null ? 0 : result.footprintMatches,
			result != null && result.restored,
			probeCallNanos / 1_000_000.0,
			result == null ? 0 : result.drawNanos / 1_000_000.0,
			result == null ? 0 : result.drawPasses,
			result == null ? 0 : result.copiedPixels,
			result == null ? 0 : result.allocatedBytesMin);
	}

	private RowBuffer prototypeRowBuffer(int depth)
	{
		while (prototypeRows.size() <= depth)
		{
			prototypeRows.add(new RowBuffer());
		}
		return prototypeRows.get(depth);
	}

	private static final class RowBuffer
	{
		private final List<MenuRenderer.Row> rows = new ArrayList<>();
		private final List<MenuRenderer.Row> pool = new ArrayList<>();
	}

	static boolean preparedFrameMatches(boolean menuOpen, Menu currentRoot, Menu preparedRoot)
	{
		return menuOpen && currentRoot == preparedRoot;
	}

	/**
	 * Whether the regular renderer should replace the vanilla menu. The opt-in prototype
	 * prepares scrollable roots and submenu trees before this path is reached.
	 */
	boolean shouldDraw()
	{
		return drawableEntries() != null;
	}

	/**
	 * @return the open menu's entries, or null if we shouldn't replace it. The client copies the
	 * array on every call, so callers fetch it once.
	 */
	private MenuEntry[] drawableEntries()
	{
		if (!canInspectMenu(failed, client.isMenuOpen(), client.isMenuScrollable()))
		{
			return null;
		}
		Menu menu = client.getMenu();
		if (menu == null)
		{
			return null;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return null;
		}
		return containsSubMenu(entries) ? null : entries;
	}

	static boolean canInspectMenu(boolean failed, boolean open, boolean scrollable)
	{
		return !failed && open && !scrollable;
	}

	static boolean containsSubMenu(MenuEntry[] entries)
	{
		for (MenuEntry entry : entries)
		{
			if (entry != null && entry.getSubMenu() != null)
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public synchronized Dimension render(Graphics2D graphics)
	{
		if (prepared)
		{
			try
			{
				boolean open = client.isMenuOpen();
				Menu currentRoot = open ? client.getMenu() : null;
				if (!open)
				{
					prototypeClosedDrops++;
				}
				else if (!preparedFrameMatches(true, currentRoot, preparedRoot))
				{
					prototypeChangedDrops++;
				}
				else if (client.isMenuScrollable() != preparedScrollable
					|| (preparedScrollable && client.getMenuScroll() != preparedScroll))
				{
					prototypeChangedDrops++;
				}
				else
				{
					String mismatch = !HARDENED_LIFECYCLE ? null
						: preparedState == null ? "missing prepared state"
						: refreshPreparedMouse(currentRoot, client.getMouseCanvasPosition());
					if (mismatch != null)
					{
						prototypeChangedDrops++;
					}
					else if (graphics.drawImage(prototypeImage, preparedX, preparedY, null))
					{
						prototypeBlits++;
					}
					else
					{
						prototypeBlitFailures++;
						prototypeBlitFailed = true;
					}
				}
			}
			catch (RuntimeException e)
			{
				prototypeBlitFailures++;
				prototypeBlitFailed = true;
				log.warn("Submenu prototype final image draw failed; native menus will be used", e);
			}
			finally
			{
				clearPrepared();
			}
			return null;
		}
		MenuEntry[] entries = drawableEntries();
		if (entries == null)
		{
			return null;
		}
		try
		{
			renderMenu(graphics, client.getMenu(), entries);
		}
		catch (RuntimeException e)
		{
			failed = true;
			log.warn("Right Click Styler failed to draw the menu; using the vanilla menu for the rest of this session", e);
		}
		return null;
	}

	private String refreshPreparedMouse(Menu currentRoot, Point mouse)
	{
		String mismatch = preparedState.mismatchReason(currentRoot, mouse);
		for (int attempt = 0; mismatch != null && mismatch.startsWith("mouse hover ")
			&& attempt < 2 && preparedState.canRetarget(mouse); attempt++)
		{
			renderPrototypeImage(preparedTree, preparedChain, preparedViewport,
				preparedRegion, mouse, preparedScroll, preparedTheme, preparedLocalLevel);
			preparedState.setRenderedMouse(mouse);
			if (!client.isMenuOpen() || client.getMenu() != preparedRoot)
			{
				return "root identity or menu close during hover redraw";
			}
			if (client.isMenuScrollable() != preparedScrollable
				|| (preparedScrollable && client.getMenuScroll() != preparedScroll))
			{
				return "scroll changed during hover redraw";
			}
			mouse = client.getMouseCanvasPosition();
			mismatch = preparedState.mismatchReason(client.getMenu(), mouse);
		}
		return mismatch;
	}

	private void renderMenu(Graphics2D graphics, Menu menu, MenuEntry[] entries)
	{
		setNativeBounds(bounds, menu);
		Theme theme = themeManager.effectiveTheme();

		fillRows(entries);

		int hoverIndex = hoverIndex(client.getMouseCanvasPosition());
		Player local = client.getLocalPlayer();
		int localLevel = local != null ? local.getCombatLevel() : 0;
		renderer.render(graphics, client, theme, config, rows, bounds, hoverIndex, localLevel);
	}

	static void setNativeBounds(Rectangle target, Menu menu)
	{
		target.setBounds(menu.getMenuX(), menu.getMenuY(), menu.getMenuWidth(), menu.getMenuHeight());
	}

	/**
	 * Entries are stored bottom-up; rows are drawn top-down. A null entry still occupies a row so
	 * the hover maths stays aligned with the vanilla click areas.
	 *
	 */
	private void fillRows(MenuEntry[] entries)
	{
		fillRows(entries, null, null, rows, rowPool);
	}

	private static void fillRows(MenuEntry[] entries, boolean[] childFlags,
		boolean[] childOpensLeft,
		List<MenuRenderer.Row> rows, List<MenuRenderer.Row> rowPool)
	{
		rows.clear();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			int slot = rows.size();
			if (slot == rowPool.size())
			{
				rowPool.add(new MenuRenderer.Row(null, null, MenuRenderer.TargetKind.OTHER));
			}
			MenuRenderer.Row row = rowPool.get(slot);
			MenuEntry entry = entries[i];
			if (entry != null)
			{
				row.set(entry.getOption(), entry.getTarget(), classify(entry),
					childFlags != null && childFlags[i],
					childOpensLeft != null && childOpensLeft[i]);
			}
			else
			{
				row.set(null, null, MenuRenderer.TargetKind.OTHER);
			}
			rows.add(row);
		}
	}

	private int hoverIndex(Point mouse)
	{
		return hoverIndex(bounds, rows.size(), mouse);
	}

	static int hoverIndex(Rectangle bounds, int rowCount, Point mouse)
	{
		if (mouse == null || mouse.getX() < bounds.x || mouse.getX() >= bounds.x + bounds.width)
		{
			return -1;
		}
		int relativeY = mouse.getY() - (bounds.y + MenuRenderer.HEADER_HEIGHT);
		if (relativeY < 0)
		{
			return -1;
		}
		int index = relativeY / MenuRenderer.ROW_HEIGHT;
		return index < rowCount ? index : -1;
	}

	static MenuRenderer.TargetKind classify(MenuEntry entry)
	{
		if (entry.getNpc() != null)
		{
			return MenuRenderer.TargetKind.NPC;
		}
		if (entry.getPlayer() != null)
		{
			return MenuRenderer.TargetKind.PLAYER;
		}
		MenuAction type = entry.getType();
		if (isGameObjectAction(type))
		{
			return MenuRenderer.TargetKind.OBJECT;
		}
		if (entry.getItemId() != NO_ITEM)
		{
			return MenuRenderer.TargetKind.ITEM;
		}
		return MenuRenderer.TargetKind.OTHER;
	}

	static boolean isGameObjectAction(MenuAction type)
	{
		if (type == null)
		{
			return false;
		}
		switch (type)
		{
			case ITEM_USE_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case EXAMINE_OBJECT:
				return true;
			default:
				return false;
		}
	}
}
