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
import net.runelite.client.util.Text;

@Slf4j
class MenuOverlay extends Overlay
{
	private static final int NO_ITEM = -1;
	private static final int EXACT_CONTRADICTION_LOG_LIMIT = 20;
	private static final int IMAGE_CACHE_MISMATCH_LOG_LIMIT = 10;

	/**
	 * How the exact-coverage reading ("new") compares with the probe's previous resolver
	 * ("old") on one frame.
	 */
	enum ExactComparison
	{
		/** The exact reading did not run: the draw never reached the marked root. */
		NOT_EVALUATED,
		/** Both resolved the same chain. */
		AGREE,
		/** Both resolved a chain, and the chains differ. Never rendered. */
		CONTRADICTORY,
		/** The old resolver proved a chain; the exact reading did not resolve one. */
		OLD_RESOLVED_NEW_UNRESOLVED,
		/** The old resolver was ambiguous; the exact reading resolved a chain. */
		OLD_AMBIGUOUS_NEW_RESOLVED,
		/** The old resolver was ambiguous and the exact reading did not resolve a chain. */
		BOTH_UNRESOLVED,
		/** A buffer, restore or geometry check failed, so neither reading may be used. */
		OLD_FAILED
	}

	enum SubmenuOutcome
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
		/** The exact reading was unevidenced, unexplained or failed. */
		FALLBACK_EXACT_UNRESOLVED,
		/** The two resolvers proved different chains. Neither is drawn. */
		FALLBACK_EXACT_CONTRADICTION,
		FALLBACK_OTHER
	}

	private final Client client;
	private final RightClickStylerConfig config;
	private final ThemeManager themeManager;
	private final MenuRenderer renderer = new MenuRenderer();
	private final NativeSubmenuProbe probe = new NativeSubmenuProbe();
	private final long[] exactComparisons = new long[ExactComparison.values().length];
	private long exactContradictionLogs;
	private long exactContradictionSuppressed;
	private ExactComparison lastExactComparison = ExactComparison.NOT_EVALUATED;
	private SubmenuOutcome lastOutcome;
	private final long[] submenuOutcomes = new long[SubmenuOutcome.values().length];
	/**
	 * Reuse the themed image when every rendering input is unchanged. The probe and every
	 * check still run each frame. Tests can turn the cache off, or re-render every Nth hit and
	 * compare pixels; production never verifies.
	 */
	private boolean imageCacheEnabled = true;
	private int imageCacheVerifyEvery;
	private final SubmenuImageCache imageCache = new SubmenuImageCache();
	private SubmenuImageCache.Miss lastImageMiss;
	private final long[] imageMisses = new long[SubmenuImageCache.Miss.values().length];
	private long imageVerifyChecks;
	private long imageVerifyMismatchTotal;
	private BufferedImage verifyImage;
	private int[] verifyExpected = new int[0];
	private int[] verifyActual = new int[0];
	private final List<MenuRenderer.Row> rows = new ArrayList<>();
	private final List<MenuRenderer.Row> rowPool = new ArrayList<>();
	private final Rectangle bounds = new Rectangle();
	private final List<RowBuffer> submenuRows = new ArrayList<>();
	private BufferedImage submenuImage;
	private int preparedX;
	private int preparedY;
	private Menu preparedRoot;
	private PreparedMenuState preparedState;
	private boolean preparedScrollable;
	private int preparedScroll;
	private boolean prepared;
	private boolean externallyConsumed;
	private long lastFailureLog;
	private long submenuBlits;
	private long submenuClosedDrops;
	private long submenuChangedDrops;
	private boolean submenuBlitFailed;

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
	}

	/** Tests only: production always uses the cache and never verifies a hit. */
	synchronized void setImageCache(boolean enabled, int verifyEvery)
	{
		imageCacheEnabled = enabled;
		imageCacheVerifyEvery = verifyEvery;
		imageCache.invalidate();
	}

	void resetFailure()
	{
		failed = false;
		submenuBlitFailed = false;
		clearPrepared();
		synchronized (this)
		{
			imageCache.invalidate();
		}
	}

	/** RuneLite reuses the root Menu object, so a new opening must end any prepared frame. */
	synchronized void menuOpened()
	{
		clearPrepared();
	}

	synchronized void discardPreparedAtEventStart()
	{
		clearPrepared();
	}

	synchronized void setExternallyConsumed(boolean consumed)
	{
		externallyConsumed = consumed;
	}

	synchronized void recordAlreadyConsumedSubmenu()
	{
		recordOutcome(SubmenuOutcome.FALLBACK_ALREADY_CONSUMED);
	}

	private void recordOutcome(SubmenuOutcome outcome)
	{
		lastOutcome = outcome;
		submenuOutcomes[outcome.ordinal()]++;
	}

	/**
	 * Prepares the themed image for a menu with submenus or a scrollable root.
	 *
	 * @param frameMouse the one mouse sample taken when {@code BeforeMenuRender} began. It is the
	 * only mouse position used for this frame: the native probe proves which submenus are open
	 * in this client cycle, and that state cannot change until the next cycle, so the live
	 * pointer is never re-read to revalidate the frame.
	 */
	synchronized boolean prepareSubmenuFrame(Menu root, MenuEntry[] rootEntries, Point frameMouse)
	{
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		if (!client.isMenuOpen())
		{
			recordOutcome(SubmenuOutcome.FALLBACK_TRANSIENT);
			return false;
		}
		boolean scrollable = client.isMenuScrollable();
		int scroll = scrollable ? client.getMenuScroll() : 0;
		if (submenuBlitFailed)
		{
			recordOutcome(SubmenuOutcome.FALLBACK_RENDER_EXCEPTION);
			return false;
		}
		boolean preparingTheme = false;
		try
		{
			int canvasWidth = client.getCanvasWidth();
			int canvasHeight = client.getCanvasHeight();
			if (canvasWidth <= 0 || canvasHeight <= 0
				|| (long) canvasWidth * canvasHeight > 8_388_608L)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_INVALID_BOUNDS);
				return false;
			}
			ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
				new Rectangle(root.getMenuX(), root.getMenuY(), root.getMenuWidth(), root.getMenuHeight()),
				rootEntries.length,
				canvasWidth, canvasHeight, scrollable, scroll);
			if (viewport == null)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_SCROLLABLE);
				return false;
			}
			SubmenuTree tree = SubmenuTree.capture(root, rootEntries,
				scrollable ? viewport.firstRow : 0);
			NativeSubmenuProbe.Result result = probe.probe(client, tree);
			if (result.state == NativeSubmenuProbe.State.GEOMETRY_CHANGED)
			{
				// Native drawing can update even a previously nonzero child rectangle.
				// Re-sample once against the current positions.
				tree = SubmenuTree.capture(root, root.getMenuEntries(),
					scrollable ? viewport.firstRow : 0);
				result = probe.probe(client, tree);
				viewport = ScrollableMenuViewport.capture(tree.nodes.get(0).bounds,
					tree.nodes.get(0).entries.length, canvasWidth, canvasHeight,
					scrollable, scroll);
			}
			// Pure classification: the rendering decision below depends on it.
			ExactComparison comparison = classifyExact(result);
			exactRecord(result, tree, comparison);
			if (viewport == null || client.isMenuScrollable() != scrollable
				|| (scrollable && client.getMenuScroll() != scroll))
			{
				recordOutcome(SubmenuOutcome.FALLBACK_SCROLLABLE);
				return false;
			}
			// The exact reading decides. Anything but one uniquely resolved chain on a
			// verified framebuffer stays native: no guess, no forced selection.
			SubmenuOutcome rejection = authoritativeRejection(result, tree);
			if (rejection != null)
			{
				recordOutcome(rejection);
				return false;
			}
			int[] chain = result.exact.chain;
			ScrollChildValidation childValidation = scrollable
				? validateScrolledChild(tree, chain, viewport, canvasHeight, result)
				: ScrollChildValidation.ATTACHED;
			if (childValidation.reason != null)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_SCROLLABLE);
				return false;
			}
			Rectangle region = new Rectangle(tree.nodes.get(0).bounds);
			for (int candidate : chain)
			{
				region.add(tree.nodes.get(candidate + 1).bounds);
			}
			region.grow(3, 3);
			region = region.intersection(new Rectangle(0, 0, canvasWidth, canvasHeight));
			if (region.width <= 0 || region.height <= 0)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_INVALID_BOUNDS);
				return false;
			}
			if (submenuImage == null || submenuImage.getWidth() != region.width
				|| submenuImage.getHeight() != region.height)
			{
				submenuImage = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB);
				imageCache.invalidate();
			}
			PreparedMenuState state = PreparedMenuState.capture(tree, chain);
			if (state.mismatchReason(client.getMenu()) != null)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_TRANSIENT);
				return false;
			}
			preparingTheme = true;
			Theme theme = themeManager.effectiveTheme();
			Player player = client.getLocalPlayer();
			int localLevel = player == null ? 0 : player.getCombatLevel();
			renderOrReuseSubmenuImage(tree, chain, viewport, region, frameMouse, scroll, theme, localLevel);
			// Structural state only: the frame's mouse sample is fixed, so pointer
			// movement since the sample is not a reason to discard native proof.
			boolean changed = !client.isMenuOpen()
				|| client.isMenuScrollable() != scrollable
				|| (scrollable && client.getMenuScroll() != scroll)
				|| state.mismatchReason(client.getMenu()) != null;
			if (changed)
			{
				recordOutcome(SubmenuOutcome.FALLBACK_TRANSIENT);
				return false;
			}
			preparedX = region.x;
			preparedY = region.y;
			preparedRoot = tree.root;
			preparedState = state;
			preparedScrollable = scrollable;
			preparedScroll = scroll;
			prepared = true;
			recordOutcome(chain.length == 0
				? SubmenuOutcome.READY_ROOT_ONLY
				: childValidation.detached ? SubmenuOutcome.READY_WITH_NATIVE_DETACHED_CHILD
				: SubmenuOutcome.READY_WITH_CHILD);
			return true;
		}
		catch (IllegalArgumentException e)
		{
			clearPrepared();
			recordOutcome(preparingTheme ? SubmenuOutcome.FALLBACK_RENDER_EXCEPTION
				: treeFailureOutcome(e.getMessage()));
			return false;
		}
		catch (RuntimeException e)
		{
			clearPrepared();
			recordOutcome(preparingTheme
				? SubmenuOutcome.FALLBACK_RENDER_EXCEPTION : SubmenuOutcome.FALLBACK_PROBE_EXCEPTION);
			long now = System.nanoTime();
			if (now - lastFailureLog >= 5_000_000_000L)
			{
				lastFailureLog = now;
				log.warn("Submenu preparation failed; native menu retained", e);
			}
			return false;
		}
	}

	/**
	 * Compares the exact reading ("new") with the probe's previous resolver ("old"). Pure and
	 * total over every probe result, so the rendering decision can rely on it. An exact
	 * ERROR counts as unresolved: it never proves a chain.
	 */
	static ExactComparison classifyExact(NativeSubmenuProbe.Result result)
	{
		ExactCoverageResolver.Outcome exact = result.exact;
		if (exact == null || exact.state == ExactCoverageResolver.State.NOT_EVALUATED)
		{
			return ExactComparison.NOT_EVALUATED;
		}
		if (!result.framebufferVerified)
		{
			return ExactComparison.OLD_FAILED;
		}
		boolean resolved = exact.state == ExactCoverageResolver.State.RESOLVED;
		if (result.state == NativeSubmenuProbe.State.NO_CHILD
			|| result.state == NativeSubmenuProbe.State.ACTIVE)
		{
			return !resolved ? ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED
				: Arrays.equals(exact.chain, result.activeChain) ? ExactComparison.AGREE
				: ExactComparison.CONTRADICTORY;
		}
		if (result.state == NativeSubmenuProbe.State.AMBIGUOUS)
		{
			return resolved ? ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED : ExactComparison.BOTH_UNRESOLVED;
		}
		return ExactComparison.OLD_FAILED;
	}

	/**
	 * The rendering gate: null only for one uniquely resolved, well-formed exact chain on
	 * a verified framebuffer that the previous resolver does not contradict. Every later
	 * structural, scroll and lifecycle check still applies to the chain it lets through.
	 */
	static SubmenuOutcome authoritativeRejection(NativeSubmenuProbe.Result result, SubmenuTree tree)
	{
		if (!result.framebufferVerified)
		{
			// Buffer, restore and geometry failures keep their existing outcomes.
			return failureOutcome(result);
		}
		ExactCoverageResolver.Outcome exact = result.exact;
		if (exact == null || exact.state == ExactCoverageResolver.State.AMBIGUOUS)
		{
			return SubmenuOutcome.FALLBACK_AMBIGUOUS;
		}
		if (exact.state != ExactCoverageResolver.State.RESOLVED || exact.explainingChains != 1
			|| !wellFormedChain(exact.chain, tree))
		{
			return SubmenuOutcome.FALLBACK_EXACT_UNRESOLVED;
		}
		if (classifyExact(result) == ExactComparison.CONTRADICTORY)
		{
			return SubmenuOutcome.FALLBACK_EXACT_CONTRADICTION;
		}
		return null;
	}

	/** Each member is a real candidate whose parent is the previous member (the root first). */
	private static boolean wellFormedChain(int[] chain, SubmenuTree tree)
	{
		int parent = -1;
		for (int candidate : chain)
		{
			if (candidate < 0 || candidate >= tree.candidateParents.size()
				|| tree.candidateParents.get(candidate) != parent)
			{
				return false;
			}
			parent = candidate;
		}
		return true;
	}

	/** Bookkeeping only: nothing here reaches the rendering decision. */
	private void exactRecord(NativeSubmenuProbe.Result result, SubmenuTree tree, ExactComparison comparison)
	{
		lastExactComparison = comparison;
		exactComparisons[comparison.ordinal()]++;
		if (comparison == ExactComparison.CONTRADICTORY)
		{
			logContradiction(result, tree);
		}
	}

	/** Both resolvers proved a chain and they differ: logged a bounded number of times per session. */
	private void logContradiction(NativeSubmenuProbe.Result result, SubmenuTree tree)
	{
		if (exactContradictionLogs++ >= EXACT_CONTRADICTION_LOG_LIMIT)
		{
			exactContradictionSuppressed++;
			return;
		}
		try
		{
			log.warn("Submenu exact-coverage contradiction; native menu kept. probe={} probeChain={} exact={} exactChain={} covered={} scroll={} root={} canvas={}x{} candidates={} bounds={} parents={}",
				result.state + ": " + result.reason, chainBounds(tree, result.activeChain), result.exact,
				chainBounds(tree, result.exact.chain), chainBounds(tree, result.exact.covered),
				client.getMenuScroll(), tree.nodes.get(0).bounds, client.getCanvasWidth(), client.getCanvasHeight(),
				tree.candidateBounds.size(), tree.candidateBounds, tree.candidateParents);
		}
		catch (RuntimeException ignored)
		{
			// Logging must never affect the rendering decision.
		}
	}

	private static String chainBounds(SubmenuTree tree, int[] chain)
	{
		StringBuilder text = new StringBuilder("[");
		for (int candidate : chain)
		{
			if (candidate >= 0 && candidate < tree.candidateBounds.size())
			{
				Rectangle bounds = tree.candidateBounds.get(candidate);
				text.append(text.length() > 1 ? " " : "").append(candidate).append('=')
					.append(bounds.x).append(',').append(bounds.y).append(',')
					.append(bounds.width).append('x').append(bounds.height);
			}
		}
		return text.append(']').toString();
	}

	/**
	 * Fills the rows of every drawn menu, then renders them into {@link #submenuImage}, or
	 * keeps the image already there when the cache is enabled and every rendering input equals
	 * the inputs it was rendered from.
	 */
	private void renderOrReuseSubmenuImage(SubmenuTree tree, int[] activeChain,
		ScrollableMenuViewport viewport, Rectangle region, Point mouse, int scroll,
		Theme theme, int localLevel)
	{
		int menus = activeChain.length + 1;
		int[] hovered = new int[menus];
		for (int depth = 0; depth < menus; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, activeChain, depth);
			RowBuffer buffer = submenuRowBuffer(depth);
			// Shallower depths are filled first, so the opening row already holds this frame's state.
			MenuRenderer.Row parentRow = depth == 0 ? null : openingRow(tree, node,
				menuNode(tree, activeChain, depth - 1), submenuRowBuffer(depth - 1).rows);
			buffer.headerKind = fillMenuRows(node, parentRow, buffer.rows, buffer.pool);
			hovered[depth] = depth == 0
				? viewport.hoverIndex(mouse)
				: hoverIndex(node.bounds, buffer.rows.size(), mouse);
		}
		if (!imageCacheEnabled)
		{
			drawSubmenuImage(submenuImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
			return;
		}
		SubmenuImageCache.Menu[] drawn = new SubmenuImageCache.Menu[menus];
		for (int depth = 0; depth < menus; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, activeChain, depth);
			RowBuffer buffer = submenuRowBuffer(depth);
			drawn[depth] = SubmenuImageCache.menu(node.bounds, node.header, buffer.headerKind,
				depth == 0 ? scroll : 0, hovered[depth], buffer.rows);
		}
		SubmenuImageCache.Key key = SubmenuImageCache.capture(theme, config, client.getModIcons(),
			localLevel, region, drawn);
		SubmenuImageCache.Miss miss = imageCache.lookup(key);
		lastImageMiss = miss;
		imageMisses[miss.ordinal()]++;
		if (miss == SubmenuImageCache.Miss.HIT)
		{
			if (imageCacheVerifyEvery > 0 && imageMisses[miss.ordinal()] % imageCacheVerifyEvery == 0
				&& !verifyReusedImage(tree, activeChain, region, hovered, scroll, theme, localLevel))
			{
				// A rendering input is missing from the key. Never show the stale image.
				imageCache.invalidate();
				drawSubmenuImage(submenuImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
				imageCache.rendered(key);
			}
			return;
		}
		// Invalid until the render completes, so a render that throws can never be reused.
		imageCache.invalidate();
		drawSubmenuImage(submenuImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
		if (key != null)
		{
			imageCache.rendered(key);
		}
	}

	private static SubmenuTree.Node menuNode(SubmenuTree tree, int[] activeChain, int depth)
	{
		return tree.nodes.get(depth == 0 ? 0 : activeChain[depth - 1] + 1);
	}

	/** Draws the rows already filled for each depth. */
	private void drawSubmenuImage(BufferedImage image, SubmenuTree tree, int[] activeChain,
		Rectangle region, int[] hovered, int scroll, Theme theme, int localLevel)
	{
		Graphics2D g = image.createGraphics();
		try
		{
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, region.width, region.height);
			g.setComposite(AlphaComposite.SrcOver);
			g.translate(-region.x, -region.y);
			for (int depth = 0; depth <= activeChain.length; depth++)
			{
				SubmenuTree.Node node = menuNode(tree, activeChain, depth);
				RowBuffer buffer = submenuRowBuffer(depth);
				renderer.render(g, client, theme, config, buffer.rows, node.bounds,
					hovered[depth], localLevel, node.header, buffer.headerKind, depth == 0 ? scroll : 0);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/** Renders the same inputs into a scratch image and compares every pixel. */
	private boolean verifyReusedImage(SubmenuTree tree, int[] activeChain, Rectangle region,
		int[] hovered, int scroll, Theme theme, int localLevel)
	{
		int width = region.width;
		int height = region.height;
		if (verifyImage == null || verifyImage.getWidth() != width || verifyImage.getHeight() != height)
		{
			verifyImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		}
		drawSubmenuImage(verifyImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
		if (verifyExpected.length != width * height)
		{
			verifyExpected = new int[width * height];
			verifyActual = new int[width * height];
		}
		// getRGB copies through the raster, so the displayed image stays GPU-accelerable.
		verifyImage.getRGB(0, 0, width, height, verifyExpected, 0, width);
		submenuImage.getRGB(0, 0, width, height, verifyActual, 0, width);
		imageVerifyChecks++;
		if (Arrays.equals(verifyExpected, verifyActual))
		{
			return true;
		}
		if (imageVerifyMismatchTotal++ < IMAGE_CACHE_MISMATCH_LOG_LIMIT)
		{
			log.warn("Submenu image cache mismatch region={} chain={}: a rendering input is missing from the cache key; re-rendered",
				region, Arrays.toString(activeChain));
		}
		return false;
	}

	private static SubmenuOutcome treeFailureOutcome(String reason)
	{
		if (reason == null)
		{
			return SubmenuOutcome.FALLBACK_OTHER;
		}
		if (reason.startsWith("menu depth limit"))
		{
			return SubmenuOutcome.FALLBACK_TREE_DEPTH;
		}
		if (reason.startsWith("menu count limit")
			|| reason.startsWith("menu total entry count limit")
			|| reason.startsWith("invalid menu entry count"))
		{
			return SubmenuOutcome.FALLBACK_TREE_COUNT;
		}
		if (reason.startsWith("menu identity cycle"))
		{
			return SubmenuOutcome.FALLBACK_TREE_CYCLE;
		}
		if (reason.contains("bounds"))
		{
			return SubmenuOutcome.FALLBACK_INVALID_BOUNDS;
		}
		return SubmenuOutcome.FALLBACK_OTHER;
	}

	static String unsafeScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight)
	{
		return validateScrolledChild(tree, chain, rootViewport, canvasHeight, null).reason;
	}

	/**
	 * @param proof the probe result the chain came from. A detached child counts as natively
	 * drawn only when that result's exact reading resolved it: every marked pixel of its visible
	 * rectangle changed.
	 */
	static ScrollChildValidation validateScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight,
		NativeSubmenuProbe.Result proof)
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
				if (!exactlyDrawn(proof, candidate))
				{
					return ScrollChildValidation.reject("active child bounds are detached from its visible parent row"
						+ " row=" + row + " rowTop=" + rowTop + " child=" + child.bounds
						+ " exclusive=" + (proof == null || candidate >= proof.changed.length
							? 0 : proof.changed[candidate])
						+ " footprintMatches=" + (proof == null ? 0 : proof.footprintMatches)
						+ " exact=" + (proof == null ? "none" : proof.exact.state));
				}
				detached = true;
			}
		}
		return detached ? ScrollChildValidation.DETACHED : ScrollChildValidation.ATTACHED;
	}

	/** The probe's own exact reading resolved a chain containing this candidate. */
	private static boolean exactlyDrawn(NativeSubmenuProbe.Result proof, int candidate)
	{
		if (proof == null || !proof.restored || !proof.framebufferVerified
			|| proof.exact.state != ExactCoverageResolver.State.RESOLVED)
		{
			return false;
		}
		for (int proved : proof.exact.chain)
		{
			if (proved == candidate)
			{
				return true;
			}
		}
		return false;
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

	private static SubmenuOutcome failureOutcome(NativeSubmenuProbe.Result result)
	{
		if (result.state == NativeSubmenuProbe.State.AMBIGUOUS)
		{
			return SubmenuOutcome.FALLBACK_AMBIGUOUS;
		}
		if (result.reason.startsWith("candidate pixel area limit"))
		{
			return SubmenuOutcome.FALLBACK_COMPLEXITY;
		}
		if ("framebuffer restoration failed".equals(result.reason))
		{
			return SubmenuOutcome.FALLBACK_RESTORE_FAILURE;
		}
		if ("native draw missed buffer".equals(result.reason))
		{
			return SubmenuOutcome.FALLBACK_NATIVE_DRAW_MISSED;
		}
		if (result.reason.contains("raster/provider")
			|| result.reason.contains("buffer identity")
			|| "invalid framebuffer".equals(result.reason))
		{
			return SubmenuOutcome.FALLBACK_BUFFER_MISMATCH;
		}
		if (result.state == NativeSubmenuProbe.State.GEOMETRY_CHANGED)
		{
			return SubmenuOutcome.FALLBACK_GEOMETRY_CHANGED;
		}
		if (result.reason.contains("geometry"))
		{
			return SubmenuOutcome.FALLBACK_INVALID_BOUNDS;
		}
		return SubmenuOutcome.FALLBACK_OTHER;
	}

	synchronized void clearPrepared()
	{
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		preparedScrollable = false;
		preparedScroll = 0;
	}

	private RowBuffer submenuRowBuffer(int depth)
	{
		while (submenuRows.size() <= depth)
		{
			submenuRows.add(new RowBuffer());
		}
		return submenuRows.get(depth);
	}

	private static final class RowBuffer
	{
		private final List<MenuRenderer.Row> rows = new ArrayList<>();
		private final List<MenuRenderer.Row> pool = new ArrayList<>();
		private MenuRenderer.TargetKind headerKind;
	}

	static boolean preparedFrameMatches(boolean menuOpen, Menu currentRoot, Menu preparedRoot)
	{
		return menuOpen && currentRoot == preparedRoot;
	}

	/**
	 * Whether the regular renderer should replace the vanilla menu. Scrollable roots and submenu
	 * trees are prepared by {@link #prepareSubmenuFrame} before this path is reached.
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
		if (externallyConsumed)
		{
			externallyConsumed = false;
			clearPrepared();
			return null;
		}
		if (prepared)
		{
			try
			{
				boolean open = client.isMenuOpen();
				Menu currentRoot = open ? client.getMenu() : null;
				if (!open)
				{
					submenuClosedDrops++;
				}
				// The event is already consumed, so a drop here leaves this frame without a
				// menu. Only structural changes may cause it; the pointer is never read here.
				else if (!preparedFrameMatches(true, currentRoot, preparedRoot)
					|| client.isMenuScrollable() != preparedScrollable
					|| (preparedScrollable && client.getMenuScroll() != preparedScroll)
					|| preparedState == null || preparedState.mismatchReason(currentRoot) != null)
				{
					submenuChangedDrops++;
					submenuBlitFailed = true;
				}
				else if (graphics.drawImage(submenuImage, preparedX, preparedY, null))
				{
					submenuBlits++;
				}
				else
				{
					submenuBlitFailed = true;
				}
			}
			catch (RuntimeException e)
			{
				submenuBlitFailed = true;
				log.warn("Submenu final image draw failed; native menus resume on subsequent frames", e);
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
		fillRows(entries, childFlags, childOpensLeft, rows, rowPool, null, null);
	}

	/** The already-filled row whose entry opened {@code node}, or null when it cannot be matched. */
	static MenuRenderer.Row openingRow(SubmenuTree tree, SubmenuTree.Node node, SubmenuTree.Node parent,
		List<MenuRenderer.Row> parentRows)
	{
		if (node.parentNode < 0 || node.parentNode >= tree.nodes.size()
			|| tree.nodes.get(node.parentNode) != parent)
		{
			return null;
		}
		int slot = parent.entries.length - 1 - node.parentEntryIndex;
		return slot >= 0 && slot < parentRows.size() ? parentRows.get(slot) : null;
	}

	/**
	 * Fills one drawn menu. A child menu takes target context from the row that opened it: its
	 * header, and any child entry repeating that row's target, colour like that row does.
	 *
	 * @param parentRow the opening row, null for the root
	 * @return the header's target kind, or null when the header is not the opening row's target
	 */
	static MenuRenderer.TargetKind fillMenuRows(SubmenuTree.Node node, MenuRenderer.Row parentRow,
		List<MenuRenderer.Row> rows, List<MenuRenderer.Row> rowPool)
	{
		MenuRenderer.TargetKind parentKind = parentRow == null ? null : parentRow.kind();
		String parentPlainTarget = null;
		if (parentKind != null && parentKind != MenuRenderer.TargetKind.OTHER && parentRow.target() != null)
		{
			parentPlainTarget = Text.removeTags(parentRow.target()).trim();
		}
		boolean inherits = parentPlainTarget != null && !parentPlainTarget.isEmpty();
		fillRows(node.entries, node.childFlags, node.childOpensLeft, rows, rowPool,
			inherits ? parentKind : null, inherits ? parentPlainTarget : null);
		return parentRow != null && node.headerFromTarget ? parentKind : null;
	}

	/**
	 * A child entry with no actor, object or item of its own that repeats the opening row's
	 * target (optionally followed by appended text, such as an ID suffix) names that same thing,
	 * so it takes that row's kind. Every other child keeps its own classification.
	 */
	static MenuRenderer.TargetKind childKind(MenuEntry entry, MenuRenderer.TargetKind parentKind,
		String parentPlainTarget)
	{
		MenuRenderer.TargetKind own = classify(entry);
		if (own != MenuRenderer.TargetKind.OTHER || parentKind == null || parentPlainTarget == null
			|| parentPlainTarget.isEmpty())
		{
			return own;
		}
		String target = entry.getTarget();
		if (target == null || !target.contains(parentPlainTarget))
		{
			return own;
		}
		String plain = Text.removeTags(target).trim();
		return plain.equals(parentPlainTarget) || plain.startsWith(parentPlainTarget + " ")
			? parentKind : own;
	}

	private static void fillRows(MenuEntry[] entries, boolean[] childFlags,
		boolean[] childOpensLeft,
		List<MenuRenderer.Row> rows, List<MenuRenderer.Row> rowPool,
		MenuRenderer.TargetKind parentKind, String parentPlainTarget)
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
				row.set(entry.getOption(), entry.getTarget(),
					parentKind == null ? classify(entry) : childKind(entry, parentKind, parentPlainTarget),
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
		if (isGroundItemAction(type) || entry.getItemId() != NO_ITEM)
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

	private static boolean isGroundItemAction(MenuAction type)
	{
		if (type == null)
		{
			return false;
		}
		switch (type)
		{
			case ITEM_USE_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case EXAMINE_ITEM_GROUND:
				return true;
			default:
				return false;
		}
	}
}
