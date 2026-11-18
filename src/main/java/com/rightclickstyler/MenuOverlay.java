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
	private static final boolean SUBMENU_TRACE = Boolean.getBoolean("rightclickstyler.submenuTrace");
	private static final boolean EXACT_EVIDENCE = Boolean.getBoolean("rightclickstyler.exactEvidence");
	// Diagnostic A/B only; production uses the hardened lifecycle.
	private static final boolean HARDENED_LIFECYCLE =
		!"baseline".equals(System.getProperty("rightclickstyler.submenuLifecycle", "hardened"));
	// Diagnostic only: proven-chain reuse is evaluated against every probe, never rendered.
	private static final boolean SUBMENU_SHADOW =
		Boolean.getBoolean("rightclickstyler.submenuShadow");
	private static final int SHADOW_MISMATCH_LOG_LIMIT = 20;
	private static final int EXACT_DETAIL_LOG_LIMIT = 10;
	/**
	 * Themed submenus require a chain the exact-coverage
	 * reading RESOLVED, and the probe's previous resolver only runs as a shadow comparator.
	 * A development override can compare the previous resolver.
	 */
	private static final boolean EXACT_AUTHORITATIVE =
		!"false".equals(System.getProperty("rightclickstyler.exactCoverageAuthoritative"));
	private static final int EXACT_CONTRADICTION_LOG_LIMIT = 20;
	/**
	 * Reuse the themed image when every rendering input is unchanged. The probe and every
	 * check still run each frame.
	 */
	private static final boolean IMAGE_CACHE =
		!"false".equals(System.getProperty("rightclickstyler.imageCache"));
	/** Diagnostic: re-render every Nth cache hit and compare pixels (0 = never). */
	private static final int IMAGE_CACHE_VERIFY_EVERY =
		Integer.getInteger("rightclickstyler.imageCacheVerify", 0);
	private static final int IMAGE_CACHE_MISMATCH_LOG_LIMIT = 10;
	/** Compact evidence records between two game ticks; overflow is counted and reported. */
	private static final int EXACT_EVIDENCE_CAPACITY = 1024;

	/**
	 * How the exact-coverage reading ("new") compares with the probe's previous resolver
	 * ("old") on one frame. The declaration order fixes the frame trace codes {@code .aDkRbf}.
	 */
	enum ExactComparison
	{
		/** The exact reading did not run: shadow disabled, or the draw never reached the marked root. */
		NOT_EVALUATED,
		/** Both resolved the same chain. */
		AGREE,
		/** Both resolved a chain, and the chains differ. Never rendered in authoritative mode. */
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

	static boolean hardenedLifecycle()
	{
		return HARDENED_LIFECYCLE;
	}

	static boolean exactCoverageAuthoritativeDefault()
	{
		return EXACT_AUTHORITATIVE;
	}

	static boolean submenuShadowEnabled()
	{
		return SUBMENU_SHADOW;
	}

	enum PrototypeOutcome
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
		/** Authoritative mode: the exact reading was unevidenced, unexplained or failed. */
		FALLBACK_EXACT_UNRESOLVED,
		/** Authoritative mode: the two resolvers proved different chains. Neither is drawn. */
		FALLBACK_EXACT_CONTRADICTION,
		FALLBACK_OTHER
	}

	private final Client client;
	private final RightClickStylerConfig config;
	private final ThemeManager themeManager;
	private final MenuRenderer renderer = new MenuRenderer();
	private final SubmenuPrototypeProbe prototypeProbe = new SubmenuPrototypeProbe();
	private final SubmenuChainShadow shadow = new SubmenuChainShadow();
	private SubmenuChainShadow.Decision shadowDecision;
	private boolean shadowFailed;
	private final long[] shadowDecisions = new long[SubmenuChainShadow.Kind.values().length];
	private final long[] shadowProbeReasons = new long[SubmenuChainShadow.ProbeReason.values().length];
	private final long[][] shadowVerdicts =
		new long[SubmenuChainShadow.Kind.values().length][SubmenuChainShadow.Verdict.values().length];
	private final long[] shadowSettledMatchAges = new long[SubmenuChainShadow.MAX_SETTLED_REUSE_CYCLES + 1];
	private long shadowNanos;
	private long shadowMaxNanos;
	private long shadowFrames;
	private long shadowMismatchLogs;
	private final long[] exactComparisons = new long[ExactComparison.values().length];
	private final long[] exactStates = new long[ExactCoverageResolver.State.values().length];
	private long exactNanos;
	private long exactMaxNanos;
	private long exactFrames;
	private long exactDetailLogs;
	private long exactDetailSuppressed;
	private long exactContradictionLogs;
	private long exactContradictionSuppressed;
	private ExactComparison lastExactComparison = ExactComparison.NOT_EVALUATED;
	/** Fixed at launch from the system property; tests set it per overlay. */
	private boolean exactAuthoritative = EXACT_AUTHORITATIVE;
	/** Set when exact-coverage bookkeeping throws. Rendering decisions never depend on it. */
	private boolean exactDiagnosticsFailed;
	private boolean streamFailed;
	private final BoundedEvidenceLog exactEvidence = new BoundedEvidenceLog(EXACT_EVIDENCE_CAPACITY);
	private long exactEvidenceRecorded;
	private long exactEvidenceEmitted;
	private long exactEvidenceDropped;
	private PrototypeOutcome lastOutcome;
	private boolean imageCacheEnabled = IMAGE_CACHE;
	private int imageCacheVerifyEvery = IMAGE_CACHE_VERIFY_EVERY;
	private final PrototypeImageCache imageCache = new PrototypeImageCache();
	private PrototypeImageCache.Miss lastImageMiss;
	private final long[] imageMisses = new long[PrototypeImageCache.Miss.values().length];
	private long imageVerifyChecks;
	private long imageVerifyMismatches;
	private long imageVerifyMismatchTotal;
	private BufferedImage verifyImage;
	private int[] verifyExpected = new int[0];
	private int[] verifyActual = new int[0];
	private PerfTrace perf = PerfTrace.DEFAULT ? new PerfTrace() : null;
	private final List<MenuRenderer.Row> rows = new ArrayList<>();
	private final List<MenuRenderer.Row> rowPool = new ArrayList<>();
	private final Rectangle bounds = new Rectangle();
	private final List<RowBuffer> prototypeRows = new ArrayList<>();
	private BufferedImage prototypeImage;
	private int preparedX;
	private int preparedY;
	private Menu preparedRoot;
	private PreparedMenuState preparedState;
	private boolean preparedScrollable;
	private int preparedScroll;
	private boolean prepared;
	private boolean externallyConsumed;
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
	// Streamed on every game tick (about 0.6s), so the ring only has to span a stalled tick.
	// A 5s window of 719 frames overflowed the former 512-frame ring in a live trace.
	private static final int FRAME_TRACE_CAPACITY = 2048;
	private final FrameTrace[] frameTraces = new FrameTrace[FRAME_TRACE_CAPACITY];
	private long frameSequence;
	private long lastReportedFrame;
	private FrameTrace currentFrameTrace;
	private FrameTrace preparedFrameTrace;
	/** The single mouse sample for the frame being prepared; diagnostics only. */
	private Point currentFrameMouse;
	private long prototypeConsumed;
	private long prototypeFinalNanos;
	private long prototypeMaxFinalNanos;
	private long prototypeFinalFrames;
	private long preparedAtNanos;
	private long prototypeReadyToFinalNanos;
	private long prototypeMaxReadyToFinalNanos;
	private long prototypeProbeSamples;
	private long prototypeCandidateObservations;
	private long prototypeVisibleCandidates;
	private long prototypeCandidatePixels;
	private int prototypeMaxVisibleCandidates;

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
			log.info("Submenu frame codes: H=child R=root T=detached-child F=fallback; C=consumed; B=blit N=native D=late-drop; durations are microseconds");
			log.info("Submenu exact-coverage codes (old=previous resolver, new=exact coverage): .=not evaluated a=agree D=CONTRADICTORY k=old resolved/new unresolved R=old ambiguous/new resolved b=both unresolved f=framebuffer check failed");
			log.info("Submenu shadow codes: .=no decision -=probe s/S=same-cycle/settled reuse matched x/X=mismatched u/U=unverified (probe not a proof); never rendered");
		}
		if (SUBMENU_TRACE && EXACT_AUTHORITATIVE)
		{
			log.info("Submenu exact-coverage resolution is AUTHORITATIVE: themed submenus need a unique exact-coverage chain; the previous resolver is a shadow comparator");
		}
		if (SUBMENU_TRACE && IMAGE_CACHE)
		{
			log.info("Submenu prototype image cache is ENABLED (verify every {} hits); the probe and every check still run each frame",
				IMAGE_CACHE_VERIFY_EVERY);
		}
		if (PerfTrace.DEFAULT)
		{
			log.info("Submenu perf trace is ENABLED: frame intervals, costs, allocation and GC every 5s");
		}
	}

	/** Tests only: production fixes these at launch from system properties. */
	synchronized void setImageCache(boolean enabled, int verifyEvery)
	{
		imageCacheEnabled = enabled;
		imageCacheVerifyEvery = verifyEvery;
		imageCache.invalidate();
	}

	/** Tests and benchmarks only. */
	synchronized PerfTrace enablePerfTrace()
	{
		if (perf == null)
		{
			perf = new PerfTrace();
		}
		return perf;
	}

	/** Tests only: production fixes the mode at launch from the system property. */
	synchronized void setExactCoverageAuthoritative(boolean authoritative)
	{
		exactAuthoritative = authoritative;
	}

	void resetFailure()
	{
		failed = false;
		prototypeBlitFailed = false;
		clearPrepared();
		synchronized (this)
		{
			shadowFailed = false;
			shadow.invalidate();
			exactDiagnosticsFailed = false;
			streamFailed = false;
			imageCache.invalidate();
		}
	}

	/** RuneLite reuses the root Menu object, so a new opening must end any chain proof. */
	synchronized void menuOpened()
	{
		if (HARDENED_LIFECYCLE)
		{
			clearPrepared();
		}
		shadow.menuOpened();
	}

	/** A BeforeMenuRender the prototype did not prepare: no proof may survive it. */
	synchronized void invalidateSubmenuShadow()
	{
		shadow.invalidate();
	}

	synchronized void discardPreparedAtEventStart()
	{
		if (prepared)
		{
			prototypeReplacedBeforeBlit++;
		}
		clearPrepared();
	}

	synchronized void setExternallyConsumed(boolean consumed)
	{
		externallyConsumed = consumed;
	}

	synchronized void recordAlreadyConsumedSubmenu()
	{
		prototypeCalls++;
		if (SUBMENU_TRACE)
		{
			currentFrameTrace = nextFrameTrace(0);
			currentFrameTrace.finalized = true;
		}
		else
		{
			frameSequence++;
		}
		recordOutcome(PrototypeOutcome.FALLBACK_ALREADY_CONSUMED);
		prototypeTransition("FALLBACK_ALREADY_CONSUMED", null, null, 0);
	}

	synchronized void recordReentrantEvent()
	{
		prototypeReentrantEvents++;
	}

	private void recordOutcome(PrototypeOutcome outcome)
	{
		lastOutcome = outcome;
		prototypeOutcomes[outcome.ordinal()]++;
		if (SUBMENU_TRACE && currentFrameTrace != null)
		{
			currentFrameTrace.outcome = outcome;
		}
	}

	private FrameTrace nextFrameTrace(int scroll)
	{
		long id = ++frameSequence;
		int slot = (int) (id % FRAME_TRACE_CAPACITY);
		FrameTrace trace = frameTraces[slot];
		if (trace == null)
		{
			trace = new FrameTrace();
			frameTraces[slot] = trace;
		}
		trace.reset(id, scroll);
		return trace;
	}

	private static final class FrameTrace
	{
		private long id;
		private long timeMs;
		private long startNanos;
		private long preparationNanos;
		private long finalNanos;
		private long readyToFinalNanos;
		private long probeNanos;
		private long exactNanos;
		private long themeNanos;
		private char imageCache = '-';
		private long candidatePixels;
		private int scroll;
		private int candidates;
		private int visibleCandidates;
		private int selected = -1;
		private int nativeDraws;
		private PrototypeOutcome outcome;
		private boolean consumed;
		private boolean finalized;
		private boolean blitted;
		private String finalReason;
		private char shadow = '.';
		private char exact = '.';

		private void reset(long frameId, int menuScroll)
		{
			id = frameId;
			timeMs = System.currentTimeMillis();
			startNanos = System.nanoTime();
			preparationNanos = finalNanos = readyToFinalNanos = probeNanos = exactNanos = themeNanos = candidatePixels = 0;
			imageCache = '-';
			scroll = menuScroll;
			candidates = visibleCandidates = nativeDraws = 0;
			selected = -1;
			outcome = null;
			consumed = finalized = blitted = false;
			finalReason = null;
			shadow = '.';
			exact = '.';
		}
	}

	private static void noteFinalReason(FrameTrace trace, String reason)
	{
		if (trace != null)
		{
			trace.finalReason = reason;
		}
	}

	synchronized void recordSubmenuConsumed()
	{
		prototypeConsumed++;
		if (SUBMENU_TRACE && preparedFrameTrace != null)
		{
			preparedFrameTrace.consumed = true;
		}
	}

	/**
	 * Prepares submenu menus and scrollable roots only under the explicit prototype flag.
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
		currentFrameMouse = frameMouse;
		shadowDecision = null;
		prototypeCalls++;
		currentFrameTrace = SUBMENU_TRACE ? nextFrameTrace(0) : null;
		if (!SUBMENU_TRACE)
		{
			frameSequence++;
		}
		if (!client.isMenuOpen())
		{
			shadow.invalidate();
			recordOutcome(PrototypeOutcome.FALLBACK_TRANSIENT);
			if (currentFrameTrace != null)
			{
				currentFrameTrace.finalized = true;
			}
			prototypeTransition(PrototypeOutcome.FALLBACK_TRANSIENT.name(), null, null, 0);
			return false;
		}
		boolean scrollable = client.isMenuScrollable();
		int scroll = scrollable ? client.getMenuScroll() : 0;
		if (currentFrameTrace != null)
		{
			currentFrameTrace.scroll = scroll;
		}
		long start = System.nanoTime();
		if (prototypeBlitFailed)
		{
			shadow.invalidate();
			recordOutcome(PrototypeOutcome.FALLBACK_RENDER_EXCEPTION);
			if (currentFrameTrace != null)
			{
				currentFrameTrace.finalized = true;
			}
			prototypeTransition("FALLBACK_RENDER_EXCEPTION: final blit disabled prototype", null, null, 0);
			return false;
		}
		boolean preparingTheme = false;
		SubmenuPrototypeProbe.Result timingResult = null;
		long timingProbeCallNanos = 0;
		boolean authoritative = exactAuthoritative;
		long allocatedAtStart = perf == null ? -1 : perf.allocatedBytes();
		SubmenuPrototypeProbe.Result evidenceResult = null;
		SubmenuTree evidenceTree = null;
		ExactComparison evidenceComparison = null;
		try
		{
			int canvasWidth = client.getCanvasWidth();
			int canvasHeight = client.getCanvasHeight();
			if (canvasWidth <= 0 || canvasHeight <= 0
				|| (long) canvasWidth * canvasHeight > 8_388_608L)
			{
				shadow.invalidate();
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
				shadow.invalidate();
				recordOutcome(PrototypeOutcome.FALLBACK_SCROLLABLE);
				prototypeTransition("FALLBACK_SCROLLABLE: invalid root scroll viewport", null, null, 0);
				return false;
			}
			SubmenuTree tree = SubmenuTree.capture(root, rootEntries,
				scrollable ? viewport.firstRow : 0);
			// Decided from public state and the frame sample before the probe can inform it.
			shadowDecide(root, tree, viewport, scrollable, scroll, canvasWidth, canvasHeight, frameMouse);
			prototypeProbe.requireExact(authoritative);
			long probeStart = System.nanoTime();
			SubmenuPrototypeProbe.Result result = prototypeProbe.probe(client, tree);
			prototypeProbeSamples++;
			if (perf != null)
			{
				perf.probe(result);
			}
			if (currentFrameTrace != null)
			{
				currentFrameTrace.nativeDraws += result.drawPasses;
			}
			long nativeDrawNanos = result.drawNanos;
			if (result.state == SubmenuPrototypeProbe.State.GEOMETRY_CHANGED)
			{
				// Native drawing can update even a previously nonzero child rectangle.
				// Re-sample once against the current positions.
				tree = SubmenuTree.capture(root, root.getMenuEntries(),
					scrollable ? viewport.firstRow : 0);
				result = prototypeProbe.probe(client, tree);
				prototypeProbeSamples++;
				if (perf != null)
				{
					perf.probe(result);
				}
				if (currentFrameTrace != null)
				{
					currentFrameTrace.nativeDraws += result.drawPasses;
				}
				nativeDrawNanos += result.drawNanos;
				viewport = ScrollableMenuViewport.capture(tree.nodes.get(0).bounds,
					tree.nodes.get(0).entries.length, canvasWidth, canvasHeight,
					scrollable, scroll);
			}
			long probeCallNanos = System.nanoTime() - probeStart;
			// Pure classification: the authoritative decision below depends on it, so it
			// never goes through the guarded diagnostic bookkeeping.
			ExactComparison comparison = classifyExact(result);
			exactRecord(result, tree, comparison, authoritative);
			if ((authoritative || SUBMENU_TRACE)
				&& (comparison == ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED
					|| comparison == ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED))
			{
				evidenceResult = result;
				evidenceTree = tree;
				evidenceComparison = comparison;
			}
			timingResult = result;
			timingProbeCallNanos = probeCallNanos;
			prototypeProbeNanos += probeCallNanos;
			prototypeDrawNanos += nativeDrawNanos;
			prototypeVisibleCandidates += result.visibleCandidates;
			prototypeCandidatePixels += result.candidatePixels;
			prototypeCandidateObservations++;
			prototypeMaxVisibleCandidates = Math.max(prototypeMaxVisibleCandidates,
				result.visibleCandidates);
			if (currentFrameTrace != null)
			{
				currentFrameTrace.probeNanos += probeCallNanos;
				currentFrameTrace.candidates = tree.candidateBounds.size();
				currentFrameTrace.visibleCandidates = result.visibleCandidates;
				currentFrameTrace.candidatePixels = result.candidatePixels;
				currentFrameTrace.selected = result.activeChain.length == 0 ? -1
					: result.activeChain[result.activeChain.length - 1];
			}
			if (viewport == null || client.isMenuScrollable() != scrollable
				|| (scrollable && client.getMenuScroll() != scroll))
			{
				shadowUnproven();
				recordOutcome(PrototypeOutcome.FALLBACK_SCROLLABLE);
				prototypeTransition("FALLBACK_SCROLLABLE: scroll state changed during probe",
					result, tree, probeCallNanos);
				return false;
			}
			int[] chain;
			if (authoritative)
			{
				// The exact reading decides. Anything but one uniquely resolved chain on a
				// verified framebuffer stays native: no guess, no forced selection.
				PrototypeOutcome rejection = authoritativeRejection(result, tree);
				if (rejection != null)
				{
					shadowUnproven();
					recordOutcome(rejection);
					prototypeTransition(rejection + ": " + result.reason + " exact=" + result.exact,
						result, tree, probeCallNanos);
					return false;
				}
				chain = result.exact.chain;
			}
			else
			{
				if (result.state != SubmenuPrototypeProbe.State.NO_CHILD
					&& result.state != SubmenuPrototypeProbe.State.ACTIVE)
				{
					shadowUnproven();
					PrototypeOutcome outcome = failureOutcome(result);
					recordOutcome(outcome);
					prototypeTransition(outcome + ": " + result.reason, result, tree, probeCallNanos);
					return false;
				}
				chain = result.activeChain;
			}
			if (currentFrameTrace != null)
			{
				currentFrameTrace.selected = chain.length == 0 ? -1 : chain[chain.length - 1];
			}
			ScrollChildValidation childValidation = scrollable
				? validateScrolledChild(tree, chain, viewport, canvasHeight, result, authoritative)
				: ScrollChildValidation.ATTACHED;
			if (childValidation.reason != null)
			{
				shadowUnproven();
				recordOutcome(PrototypeOutcome.FALLBACK_SCROLLABLE);
				prototypeTransition("FALLBACK_SCROLLABLE: " + childValidation.reason,
					result, tree, probeCallNanos);
				return false;
			}
			// Every production check on the probe has passed: this result is a proof.
			shadowProven(root, tree, viewport, scrollable, scroll, canvasWidth, canvasHeight,
				frameMouse, chain);
			Rectangle region = new Rectangle(tree.nodes.get(0).bounds);
			for (int candidate : chain)
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
				imageCache.invalidate();
			}
			PreparedMenuState state = null;
			if (HARDENED_LIFECYCLE)
			{
				state = PreparedMenuState.capture(tree, chain);
				String mismatch = state.mismatchReason(client.getMenu());
				if (mismatch != null)
				{
					recordOutcome(PrototypeOutcome.FALLBACK_TRANSIENT);
					prototypeTransition("FALLBACK_TRANSIENT: " + mismatch + " frameMouse=" + frameMouse,
						result, tree, probeCallNanos);
					return false;
				}
			}
			preparingTheme = true;
			Theme theme = themeManager.effectiveTheme();
			Player player = client.getLocalPlayer();
			int localLevel = player == null ? 0 : player.getCombatLevel();
			long renderStart = System.nanoTime();
			boolean reused = renderOrReusePrototypeImage(tree, chain, viewport, region,
				frameMouse, scroll, theme, localLevel);
			long themeNanos = System.nanoTime() - renderStart;
			prototypeRenderNanos += themeNanos;
			if (currentFrameTrace != null)
			{
				currentFrameTrace.themeNanos = themeNanos;
				currentFrameTrace.imageCache = !imageCacheEnabled ? '-' : reused ? 'h' : 'm';
			}
			if (perf != null)
			{
				perf.cost(reused ? PerfTrace.Cost.THEME_CACHE_HIT : PerfTrace.Cost.THEME_RENDER, themeNanos);
			}
			if (HARDENED_LIFECYCLE)
			{
				// Structural state only: the frame's mouse sample is fixed, so pointer
				// movement since the sample is not a reason to discard native proof.
				String mismatch = !client.isMenuOpen() ? "menu closed"
					: client.isMenuScrollable() != scrollable
						|| (scrollable && client.getMenuScroll() != scroll) ? "scroll state"
					: state.mismatchReason(client.getMenu());
				if (mismatch != null)
				{
					recordOutcome(PrototypeOutcome.FALLBACK_TRANSIENT);
					prototypeTransition("FALLBACK_TRANSIENT: " + mismatch + " after theme render frameMouse="
						+ frameMouse, result, tree, probeCallNanos);
					return false;
				}
			}
			preparedX = region.x;
			preparedY = region.y;
			preparedRoot = tree.root;
			preparedState = state;
			preparedScrollable = scrollable;
			preparedScroll = scroll;
			prepared = true;
			preparedAtNanos = System.nanoTime();
			preparedFrameTrace = currentFrameTrace;
			prototypeReady++;
			PrototypeOutcome readyOutcome = chain.length == 0
				? PrototypeOutcome.READY_ROOT_ONLY
				: childValidation.detached ? PrototypeOutcome.READY_WITH_NATIVE_DETACHED_CHILD
				: PrototypeOutcome.READY_WITH_CHILD;
			recordOutcome(readyOutcome);
			prototypeTransition(readyOutcome.name(), result, tree, probeCallNanos, chain);
			return true;
		}
		catch (IllegalArgumentException e)
		{
			shadowAbort();
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
			shadowAbort();
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
			if (currentFrameTrace != null)
			{
				currentFrameTrace.preparationNanos = elapsed;
				if (!prepared)
				{
					currentFrameTrace.finalized = true;
				}
			}
			if (evidenceResult != null)
			{
				recordExactEvidence(evidenceResult, evidenceTree, evidenceComparison, authoritative,
					lastOutcome, scroll, elapsed);
			}
			if (perf != null)
			{
				perf.cost(lastOutcome == PrototypeOutcome.READY_ROOT_ONLY ? PerfTrace.Cost.PREPARE_ROOT_ONLY
					: prepared ? PerfTrace.Cost.PREPARE_WITH_CHILD : PerfTrace.Cost.PREPARE_FALLBACK, elapsed);
				if (timingResult != null)
				{
					perf.cost(PerfTrace.Cost.PROBE, timingProbeCallNanos);
				}
				long allocatedNow = perf.allocatedBytes();
				if (allocatedAtStart >= 0 && allocatedNow >= allocatedAtStart)
				{
					perf.cost(PerfTrace.Cost.PREPARE_ALLOCATED_BYTES, allocatedNow - allocatedAtStart);
				}
			}
			long now = System.nanoTime();
			if (elapsed >= 10_000_000L
				&& now - prototypeLastSlowProbeLog >= 3_000_000_000L)
			{
				prototypeLastSlowProbeLog = now;
				log.info("Submenu prototype slow totalMs={} probeCallMs={} scroll={} rootEntries={} passes={} prepMs={} copyMs={} markerMs={} compareMs={} restoreMs={} decisionMs={} nativeDrawMs={} allocatedBytesMin={} phasesUs={{}} work={{}}",
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
					timingResult == null ? 0 : timingResult.allocatedBytesMin,
					timingResult == null ? "" : timingResult.phaseSummary(),
					timingResult == null ? "" : timingResult.counterSummary());
			}
		}
	}

	/**
	 * Compares the exact reading ("new") with the probe's previous resolver ("old"). Pure and
	 * total over every probe result, so the authoritative decision can rely on it. An exact
	 * ERROR counts as unresolved: it never proves a chain.
	 */
	static ExactComparison classifyExact(SubmenuPrototypeProbe.Result result)
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
		if (result.state == SubmenuPrototypeProbe.State.NO_CHILD
			|| result.state == SubmenuPrototypeProbe.State.ACTIVE)
		{
			return !resolved ? ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED
				: Arrays.equals(exact.chain, result.activeChain) ? ExactComparison.AGREE
				: ExactComparison.CONTRADICTORY;
		}
		if (result.state == SubmenuPrototypeProbe.State.AMBIGUOUS)
		{
			return resolved ? ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED : ExactComparison.BOTH_UNRESOLVED;
		}
		return ExactComparison.OLD_FAILED;
	}

	/**
	 * Authoritative mode's gate: null only for one uniquely resolved, well-formed exact chain on
	 * a verified framebuffer that the previous resolver does not contradict. Every later
	 * structural, scroll and lifecycle check still applies to the chain it lets through.
	 */
	static PrototypeOutcome authoritativeRejection(SubmenuPrototypeProbe.Result result, SubmenuTree tree)
	{
		if (!result.framebufferVerified)
		{
			// Buffer, restore and geometry failures keep their existing outcomes.
			return failureOutcome(result);
		}
		ExactCoverageResolver.Outcome exact = result.exact;
		if (exact == null || exact.state == ExactCoverageResolver.State.AMBIGUOUS)
		{
			return PrototypeOutcome.FALLBACK_AMBIGUOUS;
		}
		if (exact.state != ExactCoverageResolver.State.RESOLVED || exact.explainingChains != 1
			|| !wellFormedChain(exact.chain, tree))
		{
			return PrototypeOutcome.FALLBACK_EXACT_UNRESOLVED;
		}
		if (classifyExact(result) == ExactComparison.CONTRADICTORY)
		{
			return PrototypeOutcome.FALLBACK_EXACT_CONTRADICTION;
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

	/** Bookkeeping only: a failure here disables exact diagnostics and never reaches rendering. */
	private void exactRecord(SubmenuPrototypeProbe.Result result, SubmenuTree tree,
		ExactComparison comparison, boolean authoritative)
	{
		lastExactComparison = comparison;
		if (exactDiagnosticsFailed)
		{
			return;
		}
		try
		{
			ExactCoverageResolver.Outcome exact = result.exact;
			exactStates[exact.state.ordinal()]++;
			if (exact.state != ExactCoverageResolver.State.NOT_EVALUATED)
			{
				exactNanos += exact.nanos;
				exactMaxNanos = Math.max(exactMaxNanos, exact.nanos);
				exactFrames++;
			}
			exactComparisons[comparison.ordinal()]++;
			if (currentFrameTrace != null)
			{
				currentFrameTrace.exact = ".aDkRbf".charAt(comparison.ordinal()); // enum order
				currentFrameTrace.exactNanos = exact.nanos;
			}
			if (comparison == ExactComparison.CONTRADICTORY)
			{
				logContradiction(result, tree, authoritative);
			}
			else if (!authoritative && !SUBMENU_TRACE
				&& (comparison == ExactComparison.OLD_RESOLVED_NEW_UNRESOLVED
					|| comparison == ExactComparison.OLD_AMBIGUOUS_NEW_RESOLVED))
			{
				// Without the evidence stream (authoritative mode or the trace), keep a sample.
				if (exactDetailLogs++ < EXACT_DETAIL_LOG_LIMIT)
				{
					log.info("Submenu exact-coverage {} frame={} probe={} probeChain={} exact={} exactBounds={} probeBounds={} scroll={}",
						comparison, currentFrameTrace == null ? frameSequence : currentFrameTrace.id,
						result.state + ": " + result.reason, Arrays.toString(result.activeChain), exact,
						chainBounds(tree, exact.chain), chainBounds(tree, result.activeChain), client.getMenuScroll());
				}
				else
				{
					exactDetailSuppressed++;
				}
			}
		}
		catch (RuntimeException e)
		{
			disableExactDiagnostics(e);
		}
	}

	/** Both resolvers proved a chain and they differ: always prominent, never silently capped. */
	private void logContradiction(SubmenuPrototypeProbe.Result result, SubmenuTree tree, boolean authoritative)
	{
		if (exactContradictionLogs++ >= EXACT_CONTRADICTION_LOG_LIMIT)
		{
			exactContradictionSuppressed++;
			return;
		}
		log.warn("SUBMENU EXACT-COVERAGE CONTRADICTION frame={} action={} probe={} probeChain={} exact={} exactChain={} covered={} scroll={} root={} canvas={}x{} candidates={} bounds={} parents={} witnesses={} changed={} sharedWitnesses={} sharedChanged={}",
			currentFrameTrace == null ? frameSequence : currentFrameTrace.id,
			authoritative ? "native fallback; neither chain drawn" : "shadow only; the previous resolver decides",
			result.state + ": " + result.reason, chainBounds(tree, result.activeChain), result.exact,
			chainBounds(tree, result.exact.chain), chainBounds(tree, result.exact.covered),
			client.getMenuScroll(), tree.nodes.get(0).bounds, client.getCanvasWidth(), client.getCanvasHeight(),
			tree.candidateBounds.size(), tree.candidateBounds, tree.candidateParents,
			Arrays.toString(result.witnesses), Arrays.toString(result.changed),
			Arrays.toString(result.sharedWitnesses), Arrays.toString(result.sharedChanged));
	}

	/**
	 * One compact record for a frame the two resolvers decided differently without
	 * contradicting each other: every promoted resolution (old ambiguous, new resolved) and
	 * every withdrawn one (old resolved, new unresolved). Queued; logged on the next game tick.
	 */
	private void recordExactEvidence(SubmenuPrototypeProbe.Result result, SubmenuTree tree,
		ExactComparison comparison, boolean authoritative, PrototypeOutcome outcome, int scroll,
		long prepareNanos)
	{
		if (!EXACT_EVIDENCE || exactDiagnosticsFailed)
		{
			return;
		}
		try
		{
			boolean ready = outcome != null && outcome.name().startsWith("READY");
			int[] rendered = !ready ? null : authoritative ? result.exact.chain : result.activeChain;
			String record = "frame=" + (currentFrameTrace == null ? frameSequence : currentFrameTrace.id)
				+ " mode=" + (authoritative ? "authoritative" : "shadow")
				+ " comparison=" + comparison
				+ " outcome=" + outcome
				+ " rendered=" + (rendered == null ? "none" : chainBounds(tree, rendered))
				+ " exact=" + result.exact.state
				+ " exactChain=" + chainBounds(tree, result.exact.chain)
				+ " explaining=" + result.exact.explainingChains
				+ " covered=" + chainBounds(tree, result.exact.covered)
				+ " changedPixels=" + result.exact.changedPixels
				+ " probe=" + result.state + ":" + result.reason
				+ " probeChain=" + chainBounds(tree, result.activeChain)
				+ " footprintMatches=" + result.footprintMatches
				+ " candidates=" + tree.candidateBounds.size()
				+ " visible=" + result.visibleCandidates
				+ " scroll=" + scroll
				+ " root=" + rectangle(tree.nodes.get(0).bounds)
				+ " exactUs=" + result.exact.nanos / 1_000
				+ " prepUs=" + prepareNanos / 1_000;
			exactEvidenceRecorded++;
			exactEvidence.offer(record);
		}
		catch (RuntimeException e)
		{
			disableExactDiagnostics(e);
		}
	}

	private static String rectangle(Rectangle bounds)
	{
		return bounds.x + "," + bounds.y + "," + bounds.width + "x" + bounds.height;
	}

	private void disableExactDiagnostics(RuntimeException e)
	{
		exactDiagnosticsFailed = true;
		log.warn("Submenu exact-coverage diagnostics failed and are disabled; rendering decisions are unaffected", e);
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

	private void reportExact()
	{
		StringBuilder comparisons = new StringBuilder();
		for (ExactComparison comparison : ExactComparison.values())
		{
			comparisons.append(' ').append(comparison).append('=').append(exactComparisons[comparison.ordinal()]);
		}
		StringBuilder states = new StringBuilder();
		for (ExactCoverageResolver.State state : ExactCoverageResolver.State.values())
		{
			states.append(' ').append(state).append('=').append(exactStates[state.ordinal()]);
		}
		// Resolver cost and whole-frame preparation cost, measured separately over one window.
		// Preparation includes the resolver, which runs inside the probe.
		log.info("Submenu exact-coverage mode={} comparisons:{} exactStates:{} evaluated={} avgExactUs={} maxExactUs={} prepareFrames={} avgPrepareUs={} maxPrepareUs={} exactShareOfPrepare={}% contradictionLogsSuppressed={} detailLogsSuppressed={} evidenceRecorded={} evidenceEmitted={} evidenceDropped={} diagnosticsFailed={}",
			exactAuthoritative ? "authoritative" : "shadow", comparisons, states, exactFrames,
			exactFrames == 0 ? 0 : exactNanos / (exactFrames * 1_000.0), exactMaxNanos / 1_000.0,
			prototypeCalls,
			prototypeCalls == 0 ? 0 : prototypeTotalNanos / (prototypeCalls * 1_000.0),
			prototypeMaxNanos / 1_000.0,
			prototypeTotalNanos == 0 ? 0 : Math.round(1000.0 * exactNanos / prototypeTotalNanos) / 10.0,
			exactContradictionSuppressed, exactDetailSuppressed,
			exactEvidenceRecorded, exactEvidenceEmitted, exactEvidenceDropped, exactDiagnosticsFailed);
		Arrays.fill(exactComparisons, 0);
		Arrays.fill(exactStates, 0);
		exactNanos = exactMaxNanos = exactFrames = exactDetailLogs = exactDetailSuppressed = 0;
		exactContradictionLogs = exactContradictionSuppressed = 0;
	}

	/**
	 * Runs on every game tick, ahead of the 5s summary, so that prolonged scrolling cannot
	 * overwrite frame records or evidence before they are logged. Failures disable streaming
	 * only; rendering never depends on it.
	 */
	private void streamDiagnostics()
	{
		if (streamFailed)
		{
			return;
		}
		try
		{
			reportFrameTrace();
			long dropped = exactEvidence.drain(record ->
			{
				exactEvidenceEmitted++;
				log.info("Submenu exact evidence {}", record);
			});
			if (dropped > 0)
			{
				exactEvidenceDropped += dropped;
				log.warn("Submenu exact evidence queue overflowed: {} records dropped since the previous tick (capacity {})",
					dropped, exactEvidence.capacity());
			}
		}
		catch (RuntimeException e)
		{
			streamFailed = true;
			log.warn("Submenu diagnostic streaming failed and is disabled; rendering is unaffected", e);
		}
	}

	private void shadowDecide(Menu root, SubmenuTree tree, ScrollableMenuViewport viewport,
		boolean scrollable, int scroll, int canvasWidth, int canvasHeight, Point frameMouse)
	{
		if (!SUBMENU_SHADOW || shadowFailed)
		{
			return;
		}
		long start = System.nanoTime();
		try
		{
			shadowDecision = shadow.decide(new SubmenuChainShadow.Frame(root, tree, viewport,
				scrollable, scroll, canvasWidth, canvasHeight, client.getGameCycle(), frameMouse));
			shadowDecisions[shadowDecision.kind.ordinal()]++;
			if (shadowDecision.reason != null)
			{
				shadowProbeReasons[shadowDecision.reason.ordinal()]++;
			}
		}
		catch (RuntimeException e)
		{
			disableShadow(e);
		}
		finally
		{
			shadowTime(start);
		}
	}

	private void shadowProven(Menu root, SubmenuTree tree, ScrollableMenuViewport viewport,
		boolean scrollable, int scroll, int canvasWidth, int canvasHeight, Point frameMouse,
		int[] activeChain)
	{
		if (!SUBMENU_SHADOW || shadowFailed || shadowDecision == null)
		{
			return;
		}
		long start = System.nanoTime();
		try
		{
			SubmenuChainShadow.Decision decision = shadowDecision;
			Menu[] offered = decision.chain;
			SubmenuChainShadow.Verdict verdict = shadow.observeProven(decision,
				new SubmenuChainShadow.Frame(root, tree, viewport, scrollable, scroll,
					canvasWidth, canvasHeight, client.getGameCycle(), frameMouse), activeChain);
			shadowRecord(decision, verdict);
			if (verdict == SubmenuChainShadow.Verdict.MISMATCH && shadowMismatchLogs++ < SHADOW_MISMATCH_LOG_LIMIT)
			{
				log.warn("Submenu shadow MISMATCH frame={} decision={} reused={} probed={} frameMouse={} scroll={}",
					currentFrameTrace == null ? frameSequence : currentFrameTrace.id, decision,
					SubmenuChainShadow.describe(offered), SubmenuChainShadow.describe(shadow.proofChain()),
					frameMouse, scroll);
			}
		}
		catch (RuntimeException e)
		{
			disableShadow(e);
		}
		finally
		{
			shadowDecision = null;
			shadowTime(start);
		}
	}

	private void shadowUnproven()
	{
		if (!SUBMENU_SHADOW || shadowFailed || shadowDecision == null)
		{
			return;
		}
		SubmenuChainShadow.Decision decision = shadowDecision;
		shadowDecision = null;
		shadowRecord(decision, shadow.observeUnproven(decision));
	}

	/** A failure while preparing proves nothing, and the old proof is not trusted past it. */
	private void shadowAbort()
	{
		shadowUnproven();
		shadow.invalidate();
	}

	private void shadowRecord(SubmenuChainShadow.Decision decision, SubmenuChainShadow.Verdict verdict)
	{
		shadowVerdicts[decision.kind.ordinal()][verdict.ordinal()]++;
		if (decision.kind == SubmenuChainShadow.Kind.REUSE_SETTLED
			&& verdict == SubmenuChainShadow.Verdict.MATCH && decision.age < shadowSettledMatchAges.length)
		{
			shadowSettledMatchAges[decision.age]++;
		}
		if (currentFrameTrace != null)
		{
			boolean settled = decision.kind == SubmenuChainShadow.Kind.REUSE_SETTLED;
			currentFrameTrace.shadow = decision.kind == SubmenuChainShadow.Kind.PROBE ? '-'
				: verdict == SubmenuChainShadow.Verdict.MATCH ? (settled ? 'S' : 's')
				: verdict == SubmenuChainShadow.Verdict.MISMATCH ? (settled ? 'X' : 'x')
				: settled ? 'U' : 'u';
		}
	}

	private void shadowTime(long start)
	{
		long elapsed = System.nanoTime() - start;
		shadowNanos += elapsed;
		shadowMaxNanos = Math.max(shadowMaxNanos, elapsed);
		shadowFrames++;
	}

	private void disableShadow(RuntimeException e)
	{
		shadowFailed = true;
		shadowDecision = null;
		shadow.invalidate();
		log.warn("Submenu shadow validation failed and is disabled; rendering is unaffected", e);
	}

	private void reportShadow()
	{
		if (!SUBMENU_SHADOW)
		{
			return;
		}
		StringBuilder reasons = new StringBuilder();
		for (SubmenuChainShadow.ProbeReason reason : SubmenuChainShadow.ProbeReason.values())
		{
			reasons.append(' ').append(reason).append('=').append(shadowProbeReasons[reason.ordinal()]);
		}
		int same = SubmenuChainShadow.Kind.REUSE_SAME_CYCLE.ordinal();
		int settled = SubmenuChainShadow.Kind.REUSE_SETTLED.ordinal();
		int match = SubmenuChainShadow.Verdict.MATCH.ordinal();
		int mismatch = SubmenuChainShadow.Verdict.MISMATCH.ordinal();
		int unverified = SubmenuChainShadow.Verdict.UNVERIFIED.ordinal();
		log.info("Submenu shadow decisions probe={} reuseSameCycle={} reuseSettled={} sameCycleMatch={} sameCycleMismatch={} sameCycleUnverified={} settledMatch={} settledMismatch={} settledUnverified={} settledMatchByAge={} probeReasons:{} failed={} avgShadowUs={} maxShadowUs={}",
			shadowDecisions[SubmenuChainShadow.Kind.PROBE.ordinal()], shadowDecisions[same], shadowDecisions[settled],
			shadowVerdicts[same][match], shadowVerdicts[same][mismatch], shadowVerdicts[same][unverified],
			shadowVerdicts[settled][match], shadowVerdicts[settled][mismatch], shadowVerdicts[settled][unverified],
			Arrays.toString(shadowSettledMatchAges), reasons, shadowFailed,
			shadowFrames == 0 ? 0 : shadowNanos / (shadowFrames * 1_000.0), shadowMaxNanos / 1_000.0);
		Arrays.fill(shadowDecisions, 0);
		Arrays.fill(shadowProbeReasons, 0);
		for (long[] row : shadowVerdicts)
		{
			Arrays.fill(row, 0);
		}
		Arrays.fill(shadowSettledMatchAges, 0);
		shadowNanos = shadowMaxNanos = shadowFrames = shadowMismatchLogs = 0;
	}

	/**
	 * Fills the rows of every drawn menu, then renders them into {@link #prototypeImage}, or
	 * keeps the image already there when the cache is enabled and every rendering input equals
	 * the inputs it was rendered from.
	 *
	 * @return true when the existing image was reused
	 */
	private boolean renderOrReusePrototypeImage(SubmenuTree tree, int[] activeChain,
		ScrollableMenuViewport viewport, Rectangle region, Point mouse, int scroll,
		Theme theme, int localLevel)
	{
		int menus = activeChain.length + 1;
		int[] hovered = new int[menus];
		for (int depth = 0; depth < menus; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, activeChain, depth);
			RowBuffer buffer = prototypeRowBuffer(depth);
			// Shallower depths are filled first, so the opening row already holds this frame's state.
			MenuRenderer.Row parentRow = depth == 0 ? null : openingRow(tree, node,
				menuNode(tree, activeChain, depth - 1), prototypeRowBuffer(depth - 1).rows);
			buffer.headerKind = fillMenuRows(node, parentRow, buffer.rows, buffer.pool);
			hovered[depth] = depth == 0
				? viewport.hoverIndex(mouse)
				: hoverIndex(node.bounds, buffer.rows.size(), mouse);
		}
		if (!imageCacheEnabled)
		{
			drawPrototypeImage(prototypeImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
			return false;
		}
		PrototypeImageCache.Menu[] drawn = new PrototypeImageCache.Menu[menus];
		for (int depth = 0; depth < menus; depth++)
		{
			SubmenuTree.Node node = menuNode(tree, activeChain, depth);
			RowBuffer buffer = prototypeRowBuffer(depth);
			drawn[depth] = PrototypeImageCache.menu(node.bounds, node.header, buffer.headerKind,
				depth == 0 ? scroll : 0, hovered[depth], buffer.rows);
		}
		PrototypeImageCache.Key key = PrototypeImageCache.capture(theme, config, client.getModIcons(),
			localLevel, region, drawn);
		PrototypeImageCache.Miss miss = imageCache.lookup(key);
		lastImageMiss = miss;
		imageMisses[miss.ordinal()]++;
		if (miss == PrototypeImageCache.Miss.HIT)
		{
			if (imageCacheVerifyEvery > 0 && imageMisses[miss.ordinal()] % imageCacheVerifyEvery == 0
				&& !verifyReusedImage(tree, activeChain, region, hovered, scroll, theme, localLevel))
			{
				// A rendering input is missing from the key. Never show the stale image.
				imageCache.invalidate();
				drawPrototypeImage(prototypeImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
				imageCache.rendered(key);
				return false;
			}
			return true;
		}
		// Invalid until the render completes, so a render that throws can never be reused.
		imageCache.invalidate();
		drawPrototypeImage(prototypeImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
		if (key != null)
		{
			imageCache.rendered(key);
		}
		return false;
	}

	private static SubmenuTree.Node menuNode(SubmenuTree tree, int[] activeChain, int depth)
	{
		return tree.nodes.get(depth == 0 ? 0 : activeChain[depth - 1] + 1);
	}

	/** Draws the rows already filled for each depth. */
	private void drawPrototypeImage(BufferedImage image, SubmenuTree tree, int[] activeChain,
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
				RowBuffer buffer = prototypeRowBuffer(depth);
				renderer.render(g, client, theme, config, buffer.rows, node.bounds,
					hovered[depth], localLevel, node.header, buffer.headerKind, depth == 0 ? scroll : 0);
			}
		}
		finally
		{
			g.dispose();
		}
	}

	/** Diagnostic: renders the same inputs into a scratch image and compares every pixel. */
	private boolean verifyReusedImage(SubmenuTree tree, int[] activeChain, Rectangle region,
		int[] hovered, int scroll, Theme theme, int localLevel)
	{
		int width = region.width;
		int height = region.height;
		if (verifyImage == null || verifyImage.getWidth() != width || verifyImage.getHeight() != height)
		{
			verifyImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		}
		drawPrototypeImage(verifyImage, tree, activeChain, region, hovered, scroll, theme, localLevel);
		if (verifyExpected.length != width * height)
		{
			verifyExpected = new int[width * height];
			verifyActual = new int[width * height];
		}
		// getRGB copies through the raster, so the displayed image stays GPU-accelerable.
		verifyImage.getRGB(0, 0, width, height, verifyExpected, 0, width);
		prototypeImage.getRGB(0, 0, width, height, verifyActual, 0, width);
		imageVerifyChecks++;
		if (Arrays.equals(verifyExpected, verifyActual))
		{
			return true;
		}
		imageVerifyMismatchTotal++;
		if (imageVerifyMismatches++ < IMAGE_CACHE_MISMATCH_LOG_LIMIT)
		{
			log.warn("SUBMENU IMAGE CACHE MISMATCH frame={} region={} chain={}: a rendering input is missing from the cache key; re-rendered",
				currentFrameTrace == null ? frameSequence : currentFrameTrace.id, region, Arrays.toString(activeChain));
		}
		return false;
	}

	private void reportImageCache()
	{
		if (!imageCacheEnabled)
		{
			return;
		}
		long lookups = 0;
		StringBuilder misses = new StringBuilder();
		for (PrototypeImageCache.Miss miss : PrototypeImageCache.Miss.values())
		{
			lookups += imageMisses[miss.ordinal()];
			if (miss != PrototypeImageCache.Miss.HIT && imageMisses[miss.ordinal()] > 0)
			{
				misses.append(' ').append(miss).append('=').append(imageMisses[miss.ordinal()]);
			}
		}
		long hits = imageMisses[PrototypeImageCache.Miss.HIT.ordinal()];
		log.info("Submenu image cache lookups={} hits={} hitRate={}% misses:{} verifyChecks={} verifyMismatches={}",
			lookups, hits, lookups == 0 ? 0 : Math.round(1000.0 * hits / lookups) / 10.0,
			misses.length() == 0 ? " none" : misses.toString(), imageVerifyChecks, imageVerifyMismatches);
		Arrays.fill(imageMisses, 0);
		imageVerifyChecks = imageVerifyMismatches = 0;
	}

	/** Game tick: one perf window at most every 5s, whether or not the prototype is enabled. */
	synchronized void reportPerf()
	{
		if (perf == null)
		{
			return;
		}
		try
		{
			perf.report(log, imageCacheEnabled ? "imageCache=on" : "imageCache=off", System.nanoTime());
		}
		catch (RuntimeException e)
		{
			perf = null;
			log.warn("Submenu perf trace failed and is disabled; rendering is unaffected", e);
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
		return validateScrolledChild(tree, chain, rootViewport, canvasHeight, proof, false);
	}

	/**
	 * @param exactProof the chain came from the exact reading, so a detached child counts as
	 * natively drawn only when that same reading resolved it: every marked pixel of its visible
	 * rectangle changed. The checks themselves are identical in both modes.
	 */
	static ScrollChildValidation validateScrolledChild(SubmenuTree tree, int[] chain,
		ScrollableMenuViewport rootViewport, int canvasHeight,
		SubmenuPrototypeProbe.Result proof, boolean exactProof)
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
				boolean nativeDrawn = exactProof ? exactlyDrawn(proof, candidate)
					: proof != null && onProofChain
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
						+ " footprintMatches=" + (proof == null ? 0 : proof.footprintMatches)
						+ (exactProof ? " exact=" + (proof == null ? "none" : proof.exact.state) : ""));
				}
				detached = true;
			}
		}
		return detached ? ScrollChildValidation.DETACHED : ScrollChildValidation.ATTACHED;
	}

	/** The probe's own exact reading resolved a chain containing this candidate. */
	private static boolean exactlyDrawn(SubmenuPrototypeProbe.Result proof, int candidate)
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
		if (prepared && preparedFrameTrace != null && !preparedFrameTrace.finalized)
		{
			preparedFrameTrace.finalized = true;
			preparedFrameTrace.finalReason = "cleared before blit";
		}
		prepared = false;
		preparedRoot = null;
		preparedState = null;
		preparedScrollable = false;
		preparedScroll = 0;
		preparedAtNanos = 0;
		preparedFrameTrace = null;
	}

	synchronized void reportPrototype()
	{
		streamDiagnostics();
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
		log.info("Submenu prototype summary calls={} ready={} fallback={} {} consumed={} blits={} blitFailures={} closedDrops={} changedRootDrops={} replacedBeforeBlit={} reentrantEvents={} probeSamples={} avgVisibleCandidates={} maxVisibleCandidates={} avgCandidatePixels={} avgReadyToFinalMs={} maxReadyToFinalMs={} avgFinalMs={} maxFinalMs={} avgTotalMs={} avgProbeMs={} avgNativeDrawMs={} avgThemeMs={} maxTotalMs={}",
			prototypeCalls, prototypeReady, prototypeCalls - prototypeReady,
			reasons, prototypeConsumed, prototypeBlits, prototypeBlitFailures, prototypeClosedDrops, prototypeChangedDrops,
			prototypeReplacedBeforeBlit, prototypeReentrantEvents,
			prototypeProbeSamples,
			prototypeCandidateObservations == 0 ? 0 : (double) prototypeVisibleCandidates / prototypeCandidateObservations,
			prototypeMaxVisibleCandidates,
			prototypeCandidateObservations == 0 ? 0 : (double) prototypeCandidatePixels / prototypeCandidateObservations,
			prototypeFinalFrames == 0 ? 0 : prototypeReadyToFinalNanos / (prototypeFinalFrames * 1_000_000.0),
			prototypeMaxReadyToFinalNanos / 1_000_000.0,
			prototypeFinalFrames == 0 ? 0 : prototypeFinalNanos / (prototypeFinalFrames * 1_000_000.0),
			prototypeMaxFinalNanos / 1_000_000.0,
			prototypeTotalNanos / (prototypeCalls * 1_000_000.0),
			prototypeProbeNanos / (prototypeCalls * 1_000_000.0),
			prototypeDrawNanos / (prototypeCalls * 1_000_000.0),
			prototypeRenderNanos / (prototypeCalls * 1_000_000.0),
			prototypeMaxNanos / 1_000_000.0);
		reportShadow();
		reportImageCache();
		if (!exactDiagnosticsFailed)
		{
			try
			{
				reportExact();
			}
			catch (RuntimeException e)
			{
				// Runs on the game tick ahead of the plugin's own tick work; never let it escape.
				disableExactDiagnostics(e);
			}
		}
		prototypeCalls = prototypeReady = prototypeProbeNanos = prototypeDrawNanos = 0;
		prototypeRenderNanos = prototypeTotalNanos = prototypeMaxNanos = 0;
		prototypeBlits = prototypeBlitFailures = prototypeClosedDrops = prototypeChangedDrops = 0;
		prototypeReplacedBeforeBlit = prototypeReentrantEvents = 0;
		prototypeConsumed = prototypeFinalNanos = prototypeMaxFinalNanos = prototypeFinalFrames = 0;
		prototypeReadyToFinalNanos = prototypeMaxReadyToFinalNanos = 0;
		prototypeProbeSamples = prototypeCandidateObservations = prototypeVisibleCandidates = 0;
		prototypeCandidatePixels = 0;
		prototypeMaxVisibleCandidates = 0;
		Arrays.fill(prototypeOutcomes, 0);
		prototypeLastReport = now;
	}

	private void reportFrameTrace()
	{
		if (!SUBMENU_TRACE || frameSequence == lastReportedFrame)
		{
			return;
		}
		long first = Math.max(lastReportedFrame + 1, frameSequence - FRAME_TRACE_CAPACITY + 1);
		long lost = first - lastReportedFrame - 1;
		long lastEmitted = lastReportedFrame;
		long baseMs = 0;
		StringBuilder chunk = new StringBuilder(4096);
		for (long id = first; id <= frameSequence; id++)
		{
			FrameTrace frame = frameTraces[(int) (id % FRAME_TRACE_CAPACITY)];
			if (frame == null || frame.id != id)
			{
				continue;
			}
			if (!frame.finalized)
			{
				break;
			}
			if (baseMs == 0)
			{
				baseMs = frame.timeMs;
			}
			if (chunk.length() > 3500)
			{
				log.info("Submenu frames baseMs={} lost={} format=id@offsetMs:outcome/consumed/final:child,scroll,candidates,visible:prepUs,probeUs,waitUs,finalUs:draws:shadow:exact:exactUs:themeUs:imageCache {}",
					baseMs, lost, chunk.toString());
				chunk.setLength(0);
				lost = 0;
			}
			char outcome = frame.outcome == PrototypeOutcome.READY_WITH_CHILD ? 'H'
				: frame.outcome == PrototypeOutcome.READY_ROOT_ONLY ? 'R'
				: frame.outcome == PrototypeOutcome.READY_WITH_NATIVE_DETACHED_CHILD ? 'T'
				: frame.outcome == null ? '?' : 'F';
			char finalState = frame.blitted ? 'B' : !frame.finalized ? 'P'
				: outcome == 'F' ? 'N' : 'D';
			chunk.append(id).append('@').append(frame.timeMs - baseMs)
				.append(':').append(outcome).append(frame.consumed ? 'C' : '-').append(finalState)
				.append(':').append(frame.selected).append(',').append(frame.scroll)
				.append(',').append(frame.candidates).append(',').append(frame.visibleCandidates)
				.append(':').append(frame.preparationNanos / 1_000)
				.append(',').append(frame.probeNanos / 1_000)
				.append(',').append(frame.readyToFinalNanos / 1_000)
				.append(',').append(frame.finalNanos / 1_000)
				.append(':').append(frame.nativeDraws).append(':').append(frame.shadow).append(':').append(frame.exact)
				.append(':').append(frame.exactNanos / 1_000)
				.append(':').append(frame.themeNanos / 1_000).append(':').append(frame.imageCache).append(' ');
			lastEmitted = id;
			if (frame.finalReason != null)
			{
			log.info("Submenu frame final drop id={} reason={}", id, frame.finalReason);
			}
		}
		if (chunk.length() > 0)
		{
			log.info("Submenu frames baseMs={} lost={} format=id@offsetMs:outcome/consumed/final:child,scroll,candidates,visible:prepUs,probeUs,waitUs,finalUs:draws:shadow:exact:exactUs:themeUs:imageCache {}",
				baseMs, lost, chunk.toString());
		}
		lastReportedFrame = lastEmitted;
	}

	private void prototypeTransition(String state, SubmenuPrototypeProbe.Result result,
		SubmenuTree tree, long probeCallNanos)
	{
		prototypeTransition(state, result, tree, probeCallNanos,
			result == null ? new int[0] : result.activeChain);
	}

	/** @param chain the rendered chain for READY states, otherwise the previous resolver's */
	private void prototypeTransition(String state, SubmenuPrototypeProbe.Result result,
		SubmenuTree tree, long probeCallNanos, int[] chain)
	{
		// The periodic frame trace records every READY frame, including its final blit.
		// Avoid formatting large candidate arrays on the client render path.
		if (!SUBMENU_TRACE || state.startsWith("READY"))
		{
			return;
		}
		int signature = 31 * state.hashCode() + Arrays.hashCode(chain);
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
			for (int candidate : chain)
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
		log.info("Submenu prototype state={} frame={} prepSoFarMs={} rootId={} rootTextHash={} root={} scrollable={} scroll={} canvas={}x{} frameMouse={} candidates={} visibleCandidates={} candidatePixels={} bounds={} parents={} chain={} activeParents={} witnesses={} changed={} sharedWitnesses={} sharedChanged={} footprint={} footprintPixels={} footprintMatches={} restored={} probeCallMs={} nativeDrawMs={} nativeDrawPasses={} copiedPixels={} allocatedBytesMin={} authoritative={} exact={}",
			state,
			currentFrameTrace == null ? frameSequence : currentFrameTrace.id,
			currentFrameTrace == null ? 0
				: (System.nanoTime() - currentFrameTrace.startNanos) / 1_000_000.0,
			tree == null ? 0 : System.identityHashCode(tree.root),
			rootTextHash,
			tree == null ? "unknown" : tree.nodes.get(0).bounds,
			client.isMenuScrollable(), client.getMenuScroll(),
			client.getCanvasWidth(), client.getCanvasHeight(), currentFrameMouse,
			tree == null ? 0 : tree.candidateBounds.size(),
			result == null ? 0 : result.visibleCandidates,
			result == null ? 0 : result.candidatePixels,
			tree == null ? "[]" : tree.candidateBounds,
			tree == null ? "[]" : tree.candidateParents,
			Arrays.toString(chain),
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
			result == null ? 0 : result.allocatedBytesMin,
			exactAuthoritative,
			result == null ? "none" : result.exact);
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
		private MenuRenderer.TargetKind headerKind;
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
		if (externallyConsumed)
		{
			externallyConsumed = false;
			clearPrepared();
			return null;
		}
		long frameStart = perf == null ? 0 : System.nanoTime();
		if (prepared)
		{
			long finalStart = System.nanoTime();
			long blitsBefore = prototypeBlits;
			long readyToFinal = preparedAtNanos == 0 ? 0
				: Math.max(0, finalStart - preparedAtNanos);
			prototypeReadyToFinalNanos += readyToFinal;
			prototypeMaxReadyToFinalNanos = Math.max(prototypeMaxReadyToFinalNanos, readyToFinal);
			FrameTrace frameTrace = preparedFrameTrace;
			if (frameTrace != null)
			{
				frameTrace.readyToFinalNanos = readyToFinal;
			}
			try
			{
				boolean open = client.isMenuOpen();
				Menu currentRoot = open ? client.getMenu() : null;
				if (!open)
				{
					prototypeClosedDrops++;
					noteFinalReason(frameTrace, "closed");
				}
				else if (!preparedFrameMatches(true, currentRoot, preparedRoot))
				{
					prototypeChangedDrops++;
					noteFinalReason(frameTrace, "root changed");
					prototypeBlitFailed = true;
				}
				else if (client.isMenuScrollable() != preparedScrollable
					|| (preparedScrollable && client.getMenuScroll() != preparedScroll))
				{
					prototypeChangedDrops++;
					noteFinalReason(frameTrace, "scroll changed");
					prototypeBlitFailed = true;
				}
				else
				{
					// The event is already consumed, so a drop here leaves this frame without a
					// menu. Only structural changes may cause it; the pointer is never read here.
					String mismatch = !HARDENED_LIFECYCLE ? null
						: preparedState == null ? "missing prepared state"
						: preparedState.mismatchReason(currentRoot);
					if (mismatch != null)
					{
						prototypeChangedDrops++;
						noteFinalReason(frameTrace, mismatch);
						prototypeBlitFailed = true;
					}
					else if (graphics.drawImage(prototypeImage, preparedX, preparedY, null))
					{
						prototypeBlits++;
						if (frameTrace != null)
						{
							frameTrace.blitted = true;
						}
					}
					else
					{
						prototypeBlitFailures++;
						prototypeBlitFailed = true;
						noteFinalReason(frameTrace, "drawImage returned false");
					}
				}
			}
			catch (RuntimeException e)
			{
				prototypeBlitFailures++;
				prototypeBlitFailed = true;
				noteFinalReason(frameTrace, e.getClass().getSimpleName());
				log.warn("Submenu prototype final image draw failed; native menus resume on subsequent frames", e);
			}
			finally
			{
				long finalNanos = System.nanoTime() - finalStart;
				prototypeFinalFrames++;
				prototypeFinalNanos += finalNanos;
				prototypeMaxFinalNanos = Math.max(prototypeMaxFinalNanos, finalNanos);
				if (frameTrace != null)
				{
					frameTrace.finalNanos = finalNanos;
					frameTrace.finalized = true;
				}
				clearPrepared();
				if (perf != null)
				{
					perf.cost(PerfTrace.Cost.BLIT, finalNanos);
					perfFrame(prototypeBlits > blitsBefore ? PerfTrace.Frame.SUBMENU_THEMED : null, frameStart);
				}
			}
			return null;
		}
		MenuEntry[] entries = drawableEntries();
		if (entries == null)
		{
			if (perf != null)
			{
				perfFrame(null, frameStart);
			}
			return null;
		}
		long plainAllocated = perf == null ? -1 : perf.allocatedBytes();
		try
		{
			renderMenu(graphics, client.getMenu(), entries);
		}
		catch (RuntimeException e)
		{
			failed = true;
			log.warn("Right Click Styler failed to draw the menu; using the vanilla menu for the rest of this session", e);
		}
		if (perf != null)
		{
			perf.cost(PerfTrace.Cost.PLAIN_RENDER, System.nanoTime() - frameStart);
			long allocatedNow = perf.allocatedBytes();
			if (plainAllocated >= 0 && allocatedNow >= plainAllocated)
			{
				perf.cost(PerfTrace.Cost.PLAIN_ALLOCATED_BYTES, allocatedNow - plainAllocated);
			}
			perfFrame(PerfTrace.Frame.PLAIN_THEMED, frameStart);
		}
		return null;
	}

	/** @param themed what this frame drew, or null when it drew nothing of ours */
	private void perfFrame(PerfTrace.Frame themed, long frameStart)
	{
		try
		{
			PerfTrace.Frame kind = themed != null ? themed
				: client.isMenuOpen() ? PerfTrace.Frame.MENU_NATIVE : PerfTrace.Frame.MENU_CLOSED;
			perf.frame(kind, client.getFPS(), frameStart);
		}
		catch (RuntimeException e)
		{
			perf = null;
			log.warn("Submenu perf trace failed and is disabled; rendering is unaffected", e);
		}
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
