/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class)

package org.readium.r2.navigator.epub

import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.LayoutDirection
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.collection.forEach
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.viewpager.widget.ViewPager
import kotlin.math.ceil
import kotlin.reflect.KClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorationId
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.NavigatorFragment
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.R
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.R2WebView
import org.readium.r2.navigator.RestorationNotSupportedException
import org.readium.r2.navigator.ScrollModeResourceTurnGesturePolicy
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.databinding.ReadiumNavigatorViewpagerBinding
import org.readium.r2.navigator.dummyPublication
import org.readium.r2.navigator.epub.EpubNavigatorViewModel.RunScriptCommand
import org.readium.r2.navigator.epub.css.FontFamilyDeclaration
import org.readium.r2.navigator.epub.css.MutableFontFamilyDeclaration
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.buildFontFamilyDeclaration
import org.readium.r2.navigator.extensions.normalizeLocator
import org.readium.r2.navigator.extensions.optRectF
import org.readium.r2.navigator.extensions.positionsByResource
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.CompositeInputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.KeyInterceptorView
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2PagerAdapter.PageResource
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.util.createFragmentFactory
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.toAbsoluteUrl

/**
 * Factory for a [JavascriptInterface] which will be injected in the web views.
 *
 * Return `null` if you don't want to inject the interface for the given resource.
 */
public typealias JavascriptInterfaceFactory = (resource: Link) -> Any?

/**
 * Navigator for EPUB publications.
 *
 * To use this [Fragment], create a factory with `EpubNavigatorFragment.createFactory()`.
 */
@OptIn(ExperimentalReadiumApi::class, DelicateReadiumApi::class)
public class EpubNavigatorFragment internal constructor(
    publication: Publication,
    private val initialLocator: Locator?,
    readingOrder: List<Link>?,
    private val initialPreferences: EpubPreferences,
    internal val listener: Listener?,
    internal val paginationListener: PaginationListener?,
    layout: Layout,
    private val defaults: EpubDefaults,
    configuration: Configuration,
) : NavigatorFragment(publication),
    OverflowableNavigator,
    SelectableNavigator,
    DecorableNavigator,
    HyperlinkNavigator,
    Configurable<EpubSettings, EpubPreferences> {

    // Make a copy to prevent the user from modifying the configuration after initialization.
    internal val config: Configuration = configuration.copy().apply {
        servedAssets += "readium/.*"

        addFontFamilyDeclaration(FontFamily.OPEN_DYSLEXIC) {
            addFontFace {
                addSource("readium/fonts/OpenDyslexic-Regular.otf")
            }
        }
    }

    public data class Configuration internal constructor(

        /**
         * Patterns for asset paths which will be available to EPUB resources under
         * https://readium/assets/.
         *
         * The patterns can use simple glob wildcards, see:
         * https://developer.android.com/reference/android/os/PatternMatcher#PATTERN_SIMPLE_GLOB
         *
         * Use .* to serve all app assets.
         */
        @ExperimentalReadiumApi
        var servedAssets: List<String>,

        /**
         * Readium CSS reading system settings.
         *
         * See https://readium.org/readium-css/docs/CSS19-api.html#reading-system-styles
         */
        @ExperimentalReadiumApi
        var readiumCssRsProperties: RsProperties,

        /**
         * When disabled, the Android web view's `WebSettings.textZoom` will be used to adjust the
         * font size, instead of using the Readium CSS's `--USER__fontSize` variable.
         *
         * `WebSettings.textZoom` will work with more publications than `--USER__fontSize`, even the
         * ones poorly authored. However, the page width is not adjusted when changing the font
         * size to keep the optimal line length.
         *
         * See:
         *   - https://github.com/readium/mobile/issues/5
         *   - https://github.com/readium/mobile/issues/1#issuecomment-652431984
         */
        @ExperimentalReadiumApi
        @DelicateReadiumApi
        var useReadiumCssFontSize: Boolean = true,

        /**
         * Supported HTML decoration templates.
         */
        var decorationTemplates: HtmlDecorationTemplates,

        /**
         * Indicates if a user can swipe to change resources when scroll is enabled.
         */
        var disablePageTurnsWhileScrolling: Boolean,

        /**
         * Decides whether a horizontal drag in scroll mode should turn the resource.
         */
        var scrollModeResourceTurnGesturePolicy: ScrollModeResourceTurnGesturePolicy,

        /**
         * Custom [ActionMode.Callback] to be used when the user selects content.
         *
         * Provide one if you want to customize the selection context menu items.
         */
        var selectionActionModeCallback: ActionMode.Callback?,

        /**
         * Whether padding accounting for display cutouts should be applied.
         */
        var shouldApplyInsetsPadding: Boolean?,

        /**
         * Disable user selection if the publication is protected by a DRM (e.g. with LCP).
         *
         * WARNING: If you choose to disable this, you MUST remove the Copy and Share selection
         * menu items in your app. Otherwise, you will void the EDRLab certification for your
         * application. If you need help, follow up on:
         * https://github.com/readium/kotlin-toolkit/issues/299#issuecomment-1315643577
         */
        @DelicateReadiumApi
        var disableSelectionWhenProtected: Boolean,

        internal var fontFamilyDeclarations: List<FontFamilyDeclaration>,
        internal var javascriptInterfaces: Map<String, JavascriptInterfaceFactory>,
    ) {
        public constructor(
            servedAssets: List<String> = emptyList(),
            readiumCssRsProperties: RsProperties = RsProperties(),
            decorationTemplates: HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates(),
            disablePageTurnsWhileScrolling: Boolean = false,
            scrollModeResourceTurnGesturePolicy: ScrollModeResourceTurnGesturePolicy =
                ScrollModeResourceTurnGesturePolicy.Legacy,
            selectionActionModeCallback: ActionMode.Callback? = null,
            shouldApplyInsetsPadding: Boolean? = true,
        ) : this(
            servedAssets = servedAssets,
            readiumCssRsProperties = readiumCssRsProperties,
            decorationTemplates = decorationTemplates,
            disablePageTurnsWhileScrolling = disablePageTurnsWhileScrolling,
            scrollModeResourceTurnGesturePolicy = scrollModeResourceTurnGesturePolicy,
            selectionActionModeCallback = selectionActionModeCallback,
            shouldApplyInsetsPadding = shouldApplyInsetsPadding,
            disableSelectionWhenProtected = true,
            fontFamilyDeclarations = emptyList(),
            javascriptInterfaces = emptyMap()
        )

        /**
         * Registers a new factory for the [JavascriptInterface] named [name].
         *
         * Return `null` in [factory] to prevent adding the Javascript interface for a given
         * resource.
         */
        public fun registerJavascriptInterface(name: String, factory: JavascriptInterfaceFactory) {
            javascriptInterfaces += name to factory
        }

        /**
         * Adds a declaration for [fontFamily] using [builderAction].
         *
         * @param alternates Specifies a list of alternative font families used as fallbacks when
         * symbols are missing from [fontFamily].
         */
        @ExperimentalReadiumApi
        public fun addFontFamilyDeclaration(
            fontFamily: FontFamily,
            alternates: List<FontFamily> = emptyList(),
            builderAction: (MutableFontFamilyDeclaration).() -> Unit,
        ) {
            fontFamilyDeclarations += buildFontFamilyDeclaration(
                fontFamily = fontFamily.name,
                alternates = alternates.map { it.name },
                builderAction = builderAction
            )
        }

        public companion object {
            public operator fun invoke(builder: Configuration.() -> Unit): Configuration =
                Configuration().apply(builder)
        }
    }

    public interface PaginationListener {
        public fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {}
        public fun onPageLoaded() {}
    }

    public interface Listener : OverflowableNavigator.Listener, HyperlinkNavigator.Listener

    private sealed class State {
        /** The navigator just started and didn't load any resource yet. */
        data object Initializing : State()

        /** The navigator is loading the first resource at `initialResourceHref`. */
        data class Loading(val initialResourceHref: Url) : State()

        /** The navigator is fully initialized and ready for action. */
        data object Ready : State()
    }

    private var state: State = State.Initializing

    // Configurable

    override val settings: StateFlow<EpubSettings> get() = viewModel.settings

    override fun submitPreferences(preferences: EpubPreferences) {
        viewModel.submitPreferences(preferences)
    }

    /**
     * Evaluates the given JavaScript on the currently visible HTML resource.
     *
     * Note that this only work with reflowable resources.
     */
    public suspend fun evaluateJavascript(script: String): String? {
        val page = currentReflowablePageFragment ?: return null
        page.awaitLoaded()
        return page.runJavaScriptSuspend(script)
    }

    /**
     * Returns the current locator enriched with Android WebView scroll state.
     *
     * This is useful for callers which need to synchronously capture a close-time position, where a
     * coroutine-based locator refresh is not available anymore.
     */
    public fun currentLocatorWithNativeScroll(): Locator? {
        val locator = currentLocator.value
        val webView = currentReflowablePageFragment?.webView ?: return locator
        return nativeScrollSnapshot(webView)?.let(locator::withNativeScrollSnapshot) ?: locator
    }

    /**
     * Returns the current locator enriched with a stable visible-element anchor and native WebView
     * scroll state when available.
     */
    public suspend fun currentLocatorWithPrecisePosition(): Locator? {
        var locator = currentLocatorWithNativeScroll() ?: return null
        val snapshot = EpubNativeScrollSnapshot.fromLocator(locator)
        if (snapshot?.isAtResourceStart() == true) {
            return locator.withoutEpubPrecisePositionAtResourceStart()
        }
        val webView = currentReflowablePageFragment?.webView ?: return locator
        val anchorLocator = firstVisibleElementLocator() ?: return locator
        val cssSelector = anchorLocator.cssSelector ?: return locator
        val anchor = captureViewportAnchor(webView, cssSelector) ?: return locator
        locator = locator.withViewportAnchor(anchorLocator = anchorLocator, anchor = anchor)
        return locator
    }

    /**
     * Restores a locator containing a precise Android EPUB viewport position and waits until the
     * WebView scroll state is stable enough to display.
     */
    public suspend fun restorePrecisePosition(
        locator: Locator,
        options: EpubPrecisePositionRestoreOptions = EpubPrecisePositionRestoreOptions(),
    ): EpubPrecisePositionRestoreResult {
        val anchor = EpubViewportAnchor.fromLocator(locator)
        val snapshot = EpubNativeScrollSnapshot.fromLocator(locator)

        if (anchor == null && snapshot == null) {
            return EpubPrecisePositionRestoreResult(reason = "missingPrecisePosition", attempts = 0)
        }

        if (snapshot?.isAtResourceStart(options.scrollTolerancePx) == true) {
            return restoreNativeScroll(
                locator.withoutEpubPrecisePositionAtResourceStart(),
                snapshot,
                options,
            )
        }

        if (anchor != null) {
            val anchorResult = restoreViewportAnchor(locator, anchor, options)
            if (anchorResult.viewportAnchorResult?.targetReached(options.scrollTolerancePx) == true) {
                return anchorResult
            }
        }

        return snapshot
            ?.let { restoreNativeScroll(locator, it, options) }
            ?: EpubPrecisePositionRestoreResult(reason = "missingNativeScrollFallback", attempts = 0)
    }

    private val viewModel: EpubNavigatorViewModel by viewModels {
        EpubNavigatorViewModel.createFactory(
            requireActivity().application,
            publication,
            config = this.config,
            initialPreferences = initialPreferences,
            listener = listener,
            layout = layout,
            defaults = defaults
        )
    }

    private val readingOrder: List<Link> = readingOrder ?: publication.readingOrder

    private val positionsByReadingOrder: List<List<Locator>> =
        if (readingOrder != null) {
            emptyList()
        } else {
            runBlocking { publication.positionsByReadingOrder() }
        }

    internal lateinit var positions: List<Locator>

    internal lateinit var resourcePager: R2ViewPager

    private lateinit var resourcesSingle: List<PageResource>
    private lateinit var resourcesDouble: List<PageResource>

    internal var currentPagerPosition: Int = 0
    internal lateinit var adapter: R2PagerAdapter
    private lateinit var currentActivity: FragmentActivity

    private var _binding: ReadiumNavigatorViewpagerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        currentActivity = requireActivity()
        _binding = ReadiumNavigatorViewpagerBinding.inflate(inflater, container, false)
        var view: View = binding.root

        positions = positionsByReadingOrder.flatten()

        when (viewModel.layout) {
            Layout.REFLOWABLE, Layout.SCROLLED -> {
                resourcesSingle = readingOrder.mapIndexed { index, link ->
                    PageResource.EpubReflowable(
                        link = link,
                        url = viewModel.urlTo(link),
                        positionCount = positionsByReadingOrder.getOrNull(index)?.size ?: 0
                    )
                }
            }

            Layout.FIXED -> {
                val resourcesSingle = mutableListOf<PageResource>()
                val resourcesDouble = mutableListOf<PageResource>()

                // TODO needs work, currently showing two resources for fxl, needs to understand which two resources, left & right, or only right etc.
                var doublePageLeft: Link? = null
                var doublePageRight: Link?

                for ((index, link) in readingOrder.withIndex()) {
                    val url = viewModel.urlTo(link)
                    resourcesSingle.add(PageResource.EpubFxl(leftLink = link, leftUrl = url))

                    // add first page to the right,
                    if (index == 0) {
                        resourcesDouble.add(PageResource.EpubFxl(rightLink = link, rightUrl = url))
                    } else {
                        // add double pages, left & right
                        if (doublePageLeft == null) {
                            doublePageLeft = link
                        } else {
                            doublePageRight = link
                            resourcesDouble.add(
                                PageResource.EpubFxl(
                                    leftLink = doublePageLeft,
                                    leftUrl = viewModel.urlTo(doublePageLeft),
                                    rightLink = doublePageRight,
                                    rightUrl = viewModel.urlTo(doublePageRight)
                                )
                            )
                            doublePageLeft = null
                        }
                    }
                }
                // add last page if there is only a left page remaining
                if (doublePageLeft != null) {
                    resourcesDouble.add(
                        PageResource.EpubFxl(
                            leftLink = doublePageLeft,
                            leftUrl = viewModel.urlTo(doublePageLeft)
                        )
                    )
                }

                this.resourcesSingle = resourcesSingle
                this.resourcesDouble = resourcesDouble
            }
        }

        resourcePager = binding.resourcePager
        resetResourcePager()

        // Fixed layout publications cannot intercept JS events yet.
        if (publication.metadata.layout == Layout.FIXED) {
            view = KeyInterceptorView(view, inputListener)
        }

        return view
    }

    private fun resetResourcePager() {
        val parent = requireNotNull(resourcePager.parent as? ConstraintLayout) {
            "The parent view of the EPUB `resourcePager` must be a ConstraintLayout"
        }
        // We need to null out the adapter explicitly, otherwise the page fragments will leak.
        resourcePager.adapter = null
        parent.removeView(resourcePager)

        resourcePager = R2ViewPager(requireContext())
        resourcePager.id = R.id.resourcePager
        resourcePager.publicationType = when (publication.metadata.layout) {
            Layout.REFLOWABLE, Layout.SCROLLED, null -> R2ViewPager.PublicationType.EPUB
            Layout.FIXED -> R2ViewPager.PublicationType.FXL
        }
        resourcePager.setBackgroundColor(viewModel.settings.value.effectiveBackgroundColor)
        // Let the page views handle the keyboard events.
        resourcePager.isFocusable = false
        resourcePager.addOnPageChangeListener(PageChangeListener())

        parent.addView(resourcePager)

        resetResourcePagerAdapter()
    }

    private inner class PageChangeListener : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            currentReflowablePageFragment?.webView?.let { webView ->
                if (viewModel.isScrollEnabled.value) {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.scrollToStart()
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.scrollToEnd()
                    }
                } else {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.setCurrentItem(0, false)
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.setCurrentItem(webView.numPages - 1, false)
                    }
                }
            }
            currentPagerPosition = position // Update current position

            notifyCurrentLocation()
        }
    }

    private fun resetResourcePagerAdapter() {
        adapter = when (publication.metadata.layout) {
            Layout.REFLOWABLE, Layout.SCROLLED, null -> {
                R2PagerAdapter(childFragmentManager, resourcesSingle)
            }
            Layout.FIXED -> {
                when (viewModel.dualPageMode) {
                    // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                    DualPage.OFF, DualPage.AUTO -> {
                        R2PagerAdapter(childFragmentManager, resourcesSingle)
                    }
                    DualPage.ON -> {
                        R2PagerAdapter(childFragmentManager, resourcesDouble)
                    }
                }
            }
        }
        adapter.listener = PagerAdapterListener()
        resourcePager.adapter = adapter
        resourcePager.direction = overflow.value.readingProgression
        resourcePager.layoutDirection = when (settings.value.readingProgression) {
            ReadingProgression.RTL -> LayoutDirection.RTL
            ReadingProgression.LTR -> LayoutDirection.LTR
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events
                    .onEach(::handleEvent)
                    .launchIn(this)

                var previousSettings = viewModel.settings.value
                viewModel.settings
                    .onEach {
                        onSettingsChange(previousSettings, it)
                        previousSettings = it
                    }
                    .launchIn(this)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.withStarted {
                // Restore the last locator before a configuration change (e.g. screen rotation), or the
                // initial locator when given.
                val locator = savedInstanceState?.let {
                    BundleCompat.getParcelable(
                        it,
                        "locator",
                        Locator::class.java
                    )
                }
                    ?: initialLocator
                if (locator != null) {
                    go(locator)
                }
            }
        }
    }

    private fun handleEvent(event: EpubNavigatorViewModel.Event) {
        when (event) {
            is EpubNavigatorViewModel.Event.RunScript -> {
                run(event.command)
            }
            is EpubNavigatorViewModel.Event.OpenInternalLink -> {
                go(event.target)
            }
            EpubNavigatorViewModel.Event.InvalidateViewPager -> {
                invalidateResourcePager()
            }
        }
    }

    private fun invalidateResourcePager() {
        val locator = currentLocator.value
        resetResourcePager()
        go(locator)
    }

    private fun onSettingsChange(previous: EpubSettings, new: EpubSettings) {
        if (previous.effectiveBackgroundColor != new.effectiveBackgroundColor) {
            resourcePager.setBackgroundColor(new.effectiveBackgroundColor)
        }

        if (viewModel.layout == Layout.REFLOWABLE) {
            if (previous.fontSize != new.fontSize) {
                r2PagerAdapter?.setFontSize(new.fontSize)
            }
        }
    }

    private fun R2PagerAdapter.setFontSize(fontSize: Double) {
        if (config.useReadiumCssFontSize) return

        mFragments.forEach { _, fragment ->
            (fragment as? R2EpubPageFragment)?.setFontSize(fontSize)
        }
    }

    private inner class PagerAdapterListener : R2PagerAdapter.Listener {
        override fun onCreatePageFragment(fragment: Fragment) {
            if (viewModel.layout == Layout.REFLOWABLE) {
                if (!config.useReadiumCssFontSize) {
                    (fragment as? R2EpubPageFragment)?.setFontSize(settings.value.fontSize)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("locator", currentLocator.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (publication == dummyPublication) {
            throw RestorationNotSupportedException
        }

        notifyCurrentLocation()
    }

    @OptIn(DelicateReadiumApi::class)
    override fun go(locator: Locator, animated: Boolean): Boolean {
        @Suppress("NAME_SHADOWING")
        val locator = publication.normalizeLocator(locator)

        if (state == State.Initializing) {
            state = State.Loading(locator.href)
        }

        listener?.onJumpToLocator(locator)

        val href = locator.href.removeFragment()

        fun setCurrent(resources: List<PageResource>) {
            val page = resources.withIndex().firstOrNull { (_, res) ->
                when (res) {
                    is PageResource.EpubReflowable ->
                        res.link.url().isEquivalent(href)
                    is PageResource.EpubFxl ->
                        res.leftUrl?.toString()?.endsWith(href.toString()) == true || res.rightUrl?.toString()?.endsWith(
                            href.toString()
                        ) == true
                    else -> false
                }
            } ?: return
            val (index, _) = page

            if (resourcePager.currentItem != index) {
                resourcePager.currentItem = index
            }
            r2PagerAdapter?.loadLocatorAt(index, locator)
        }

        if (publication.metadata.layout != Layout.FIXED) {
            setCurrent(resourcesSingle)
        } else {
            when (viewModel.dualPageMode) {
                // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                DualPage.OFF, DualPage.AUTO -> {
                    setCurrent(resourcesSingle)
                }
                DualPage.ON -> {
                    setCurrent(resourcesDouble)
                }
            }
        }

        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated)
    }

    private fun run(commands: List<RunScriptCommand>) {
        commands.forEach { run(it) }
    }

    private fun run(command: RunScriptCommand) {
        when (command.scope) {
            RunScriptCommand.Scope.CurrentResource -> {
                currentReflowablePageFragment
                    ?.runJavaScript(command.script)
            }
            RunScriptCommand.Scope.LoadedResources -> {
                r2PagerAdapter?.mFragments?.forEach { _, fragment ->
                    (fragment as? R2EpubPageFragment)
                        ?.takeIf { it.isLoaded.value }
                        ?.runJavaScript(command.script)
                }
            }
            is RunScriptCommand.Scope.LoadedResource -> {
                loadedFragmentForHref(command.scope.href)
                    ?.takeIf { it.isLoaded.value }
                    ?.runJavaScript(command.script)
            }
            is RunScriptCommand.Scope.WebView -> {
                command.scope.webView.runJavaScript(command.script)
            }
        }
    }

    // VisualNavigator

    override val publicationView: View
        get() = requireView()

    override val overflow: StateFlow<OverflowableNavigator.Overflow>
        get() = viewModel.overflow

    private val inputListener = CompositeInputListener()

    override fun addInputListener(listener: InputListener) {
        inputListener.add(listener)
    }

    override fun removeInputListener(listener: InputListener) {
        inputListener.remove(listener)
    }

    // SelectableNavigator

    override suspend fun currentSelection(): Selection? {
        val fragment = currentReflowablePageFragment ?: return null
        val json =
            fragment.runJavaScriptSuspend("readium.getCurrentSelection();")
                .takeIf { it != "null" }
                ?.let { tryOrLog { JSONObject(it) } }
                ?: return null

        val rect = json.optRectF("rect")
            ?.run { adjustedToViewport() }

        return Selection(
            locator = currentLocator.value.copy(
                text = Locator.Text.fromJSON(json.optJSONObject("text"))
            ),
            rect = rect
        )
    }

    override fun clearSelection() {
        run(viewModel.clearSelection())
    }

    private fun PointF.adjustedToViewport(): PointF =
        currentReflowablePageFragment?.paddingTop?.let { top ->
            PointF(x, y + top)
        } ?: this

    private fun RectF.adjustedToViewport(): RectF =
        currentReflowablePageFragment?.paddingTop?.let { topOffset ->
            RectF(left, top + topOffset, right, bottom)
        } ?: this

    // DecorableNavigator

    override fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean =
        viewModel.supportsDecorationStyle(style)

    override fun addDecorationListener(group: String, listener: DecorableNavigator.Listener) {
        viewModel.addDecorationListener(group, listener)
    }

    override fun removeDecorationListener(listener: DecorableNavigator.Listener) {
        viewModel.removeDecorationListener(listener)
    }

    @OptIn(DelicateReadiumApi::class)
    override suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        @Suppress("NAME_SHADOWING")
        val decorations = decorations
            .map { it.copy(locator = publication.normalizeLocator(it.locator)) }

        run(viewModel.applyDecorations(decorations, group))
    }

    // R2BasicWebView.Listener

    internal val webViewListener: R2BasicWebView.Listener = WebViewListener()

    private inner class WebViewListener : R2BasicWebView.Listener {

        override val readingProgression: ReadingProgression
            get() = viewModel.readingProgression

        override val verticalText: Boolean
            get() = viewModel.verticalText

        override fun onResourceLoaded(webView: R2BasicWebView, link: Link) {
            run(viewModel.onResourceLoaded(webView, link))
        }

        override fun onPageLoaded(webView: R2BasicWebView, link: Link) {
            paginationListener?.onPageLoaded()

            val href = link.url()
            if (state is State.Initializing || (state as? State.Loading)?.initialResourceHref?.isEquivalent(
                    href
                ) == true
            ) {
                state = State.Ready
            }

            notifyCurrentLocation()
        }

        override fun javascriptInterfacesForResource(link: Link): Map<String, Any?> =
            config.javascriptInterfaces.mapValues { (_, factory) -> factory(link) }

        override fun onTap(point: PointF): Boolean =
            inputListener.onTap(TapEvent(point))

        override fun onDragStart(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Start, event)

        override fun onDragMove(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Move, event)

        override fun onDragEnd(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.End, event)

        private fun onDrag(type: DragEvent.Type, event: R2BasicWebView.DragEvent): Boolean =
            inputListener.onDrag(
                DragEvent(
                    type = type,
                    start = event.startPoint.adjustedToViewport(),
                    offset = event.offset
                )
            )

        override fun onKey(event: KeyEvent): Boolean =
            inputListener.onKey(event)

        override fun onDecorationActivated(
            id: DecorationId,
            group: String,
            rect: RectF,
            point: PointF,
        ): Boolean =
            viewModel.onDecorationActivated(
                id = id,
                group = group,
                rect = rect.adjustedToViewport(),
                point = point.adjustedToViewport()
            )

        override fun onProgressionChanged() {
            notifyCurrentLocation()
        }

        override fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToPreviousResource(jump = jump, animated = animated)
        }

        override fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToNextResource(jump = jump, animated = animated)
        }

        override val selectionActionModeCallback: ActionMode.Callback?
            get() = config.selectionActionModeCallback

        /**
         * Prevents opening external links in the web view and handles internal links.
         */
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toAbsoluteUrl() ?: return false
            viewModel.navigateToUrl(url)
            return true
        }

        override fun onFootnoteLinkActivated(
            url: AbsoluteUrl,
            context: HyperlinkNavigator.FootnoteContext,
        ) {
            viewModel.navigateToUrl(url, context)
        }

        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? =
            viewModel.shouldInterceptRequest(request)

        override fun resourceAtUrl(url: AbsoluteUrl): Resource? =
            viewModel.internalLinkFromUrl(url)
                ?.let { publication.get(it) }
    }

    override fun goForward(animated: Boolean): Boolean {
        if (publication.metadata.layout == Layout.FIXED) {
            return goToNextResource(jump = false, animated = animated)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollRight(animated)

            ReadingProgression.RTL ->
                webView.scrollLeft(animated)
        }
        return true
    }

    override fun goBackward(animated: Boolean): Boolean {
        if (publication.metadata.layout == Layout.FIXED) {
            return goToPreviousResource(jump = false, animated = animated)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollLeft(animated)

            ReadingProgression.RTL ->
                webView.scrollRight(animated)
        }
        return true
    }

    private fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
        val adapter = resourcePager.adapter ?: return false
        if (resourcePager.currentItem >= adapter.count - 1) {
            return false
        }

        if (jump) {
            locatorToNextResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem + 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(webView.numPages - 1, false)
            } else {
                webView.setCurrentItem(0, false)
            }
        }

        return true
    }

    private fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
        if (resourcePager.currentItem <= 0) {
            return false
        }

        if (jump) {
            locatorToPreviousResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem - 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(0, false)
            } else {
                webView.setCurrentItem(webView.numPages - 1, false)
            }
        }

        return true
    }

    private fun locatorToPreviousResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem - 1)

    private fun locatorToNextResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem + 1)

    private fun locatorToResourceAtIndex(index: Int): Locator? =
        readingOrder.getOrNull(index)
            ?.let { publication.locatorFromLink(it) }

    private data class AwaitedReflowableWebView(
        val webView: R2WebView,
        val attempts: Int,
    )

    private suspend fun restoreViewportAnchor(
        locator: Locator,
        anchor: EpubViewportAnchor,
        options: EpubPrecisePositionRestoreOptions,
    ): EpubPrecisePositionRestoreResult {
        go(locator, animated = false)
        val awaited = awaitCurrentReflowableWebView(options)
            ?: return EpubPrecisePositionRestoreResult(
                reason = "missingVisibleWebView",
                attempts = options.maxWaitAttempts,
            )

        val result = applyViewportAnchor(awaited.webView, anchor)
        return EpubPrecisePositionRestoreResult(
            reason = when {
                result?.targetReached(options.scrollTolerancePx) == true -> "viewportAnchorRestored"
                result == null -> "viewportAnchorNoResult"
                else -> "viewportAnchorMissed"
            },
            attempts = awaited.attempts,
            viewportAnchorResult = result,
        )
    }

    private suspend fun restoreNativeScroll(
        locator: Locator,
        snapshot: EpubNativeScrollSnapshot,
        options: EpubPrecisePositionRestoreOptions,
    ): EpubPrecisePositionRestoreResult {
        go(locator, animated = false)
        val awaited = awaitCurrentReflowableWebView(options)
            ?: return EpubPrecisePositionRestoreResult(
                reason = "missingVisibleWebView",
                attempts = options.maxWaitAttempts,
            )

        var previousContentHeight: Int? = null
        var stableContentHeightAttempts = 0
        var stableScrollAttempts = 0
        var lastResult: EpubNativeScrollRestoreResult? = null
        var savedLayoutReady = false

        for (attempt in 0..options.settleMaxAttempts) {
            val result = applyNativeScrollSnapshot(awaited.webView, snapshot, options)
            lastResult = result
            val contentHeight = result?.contentHeight
            stableContentHeightAttempts =
                if (contentHeight != null && contentHeightIsStable(previousContentHeight, contentHeight, options)) {
                    stableContentHeightAttempts + 1
                } else {
                    0
                }
            previousContentHeight = contentHeight
            savedLayoutReady = result
                ?.let {
                    snapshot.savedLayoutIsReadyForRestore(
                        currentContentHeight = it.contentHeight,
                        currentViewportWidth = it.viewportWidth,
                        currentViewportHeight = it.viewportHeight,
                        heightTolerancePx = options.heightTolerancePx,
                        viewportTolerancePx = options.viewportTolerancePx,
                    )
                }
                ?: false
            stableScrollAttempts =
                if (result?.targetWasStable(options.scrollTolerancePx) == true) {
                    stableScrollAttempts + 1
                } else {
                    0
                }
            val settled = result?.targetReached(options.scrollTolerancePx) == true &&
                savedLayoutReady &&
                stableContentHeightAttempts >= options.stableContentAttempts &&
                stableScrollAttempts >= options.stableScrollAttempts

            if (settled) {
                return EpubPrecisePositionRestoreResult(
                    reason = "settled",
                    attempts = attempt + 1,
                    nativeScrollResult = result,
                    savedLayoutReady = savedLayoutReady,
                    stableContentHeightAttempts = stableContentHeightAttempts,
                    stableScrollAttempts = stableScrollAttempts,
                )
            }

            if (attempt < options.settleMaxAttempts) {
                delay(options.settleRetryDelayMs)
            }
        }

        return EpubPrecisePositionRestoreResult(
            reason = "maxAttempts",
            attempts = options.settleMaxAttempts + 1,
            nativeScrollResult = lastResult,
            savedLayoutReady = savedLayoutReady,
            stableContentHeightAttempts = stableContentHeightAttempts,
            stableScrollAttempts = stableScrollAttempts,
        )
    }

    private suspend fun awaitCurrentReflowableWebView(
        options: EpubPrecisePositionRestoreOptions,
    ): AwaitedReflowableWebView? {
        for (attempt in 0..options.maxWaitAttempts) {
            val page = currentReflowablePageFragment
            val webView = page?.webView
            if (page?.isLoaded?.value == true && webView != null) {
                return AwaitedReflowableWebView(webView, attempt + 1)
            }
            if (attempt < options.maxWaitAttempts) {
                delay(options.retryDelayMs)
            }
        }
        return null
    }

    private fun nativeScrollSnapshot(webView: R2WebView): EpubNativeScrollSnapshot? {
        val contentHeight = readerWebViewContentHeight(webView)
        val vertical = webView.scrollY != 0 ||
            webView.canScrollVertically(1) ||
            webView.canScrollVertically(-1)
        val horizontal = webView.scrollX != 0 ||
            webView.canScrollHorizontally(1) ||
            webView.canScrollHorizontally(-1)
        val progression = if (vertical) {
            contentHeight
                ?.takeIf { it > 0 }
                ?.let { (webView.scrollY.toDouble() / it).coerceIn(0.0, 1.0) }
        } else {
            null
        }
        return EpubNativeScrollSnapshot(
            scrollX = webView.scrollX,
            scrollY = webView.scrollY,
            viewportWidth = webView.width,
            viewportHeight = webView.height,
            contentHeight = contentHeight,
            progression = progression,
            axis = when {
                vertical -> "vertical"
                horizontal -> "horizontal"
                else -> "none"
            },
        )
    }

    private fun applyNativeScrollSnapshot(
        webView: R2WebView,
        snapshot: EpubNativeScrollSnapshot,
        options: EpubPrecisePositionRestoreOptions,
    ): EpubNativeScrollRestoreResult? {
        val contentHeight = readerWebViewContentHeight(webView)
        val targetScrollY = snapshot.targetScrollYForContentHeight(
            currentContentHeight = contentHeight,
            currentViewportWidth = webView.width,
            currentViewportHeight = webView.height,
            heightTolerancePx = options.heightTolerancePx,
            viewportTolerancePx = options.viewportTolerancePx,
        )
        val scrollYBeforeApply = webView.scrollY
        webView.scrollTo(
            snapshot.scrollX.coerceAtLeast(0),
            targetScrollY,
        )
        return EpubNativeScrollRestoreResult(
            contentHeight = contentHeight,
            viewportWidth = webView.width,
            viewportHeight = webView.height,
            targetScrollY = targetScrollY,
            scrollYBeforeApply = scrollYBeforeApply,
            actualScrollY = webView.scrollY,
        )
    }

    private fun readerWebViewContentHeight(webView: R2WebView): Int? =
        webView.contentHeight
            .takeIf { it > 0 }

    private fun contentHeightIsStable(
        previous: Int?,
        current: Int,
        options: EpubPrecisePositionRestoreOptions,
    ): Boolean =
        previous != null && kotlin.math.abs(previous - current) <= options.heightTolerancePx

    private suspend fun captureViewportAnchor(
        webView: R2WebView,
        cssSelector: String,
    ): EpubViewportAnchor? {
        val result = webView.runJavaScriptSuspend(viewportAnchorCaptureScript(cssSelector))
        val payload = decodeJavascriptStringResult(result) ?: return null
        return runCatching {
            EpubViewportAnchor.fromCaptureJson(cssSelector, JSONObject(payload))
        }.getOrNull()
    }

    private suspend fun applyViewportAnchor(
        webView: R2WebView,
        anchor: EpubViewportAnchor,
    ): EpubViewportAnchorRestoreResult? {
        val result = webView.runJavaScriptSuspend(viewportAnchorRestoreScript(anchor))
        return parseViewportAnchorRestoreResult(result, anchor.elementTop)
    }

    private fun viewportAnchorCaptureScript(cssSelector: String): String {
        val selector = JSONObject.quote(cssSelector)
        return """
            (function () {
                var selector = $selector;
                function cssEscape(value) {
                    if (window.CSS && window.CSS.escape) {
                        return window.CSS.escape(value);
                    }
                    return String(value).replace(/([^a-zA-Z0-9_-])/g, '\\$1');
                }
                function attributeSelector(name, value) {
                    var escaped = String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
                    return '[' + name + '="' + escaped + '"]';
                }
                function stableSelector(element) {
                    var id = element.getAttribute('id') || '';
                    if (id && !/^player[0-9]*$/i.test(id)) {
                        return '#' + cssEscape(id);
                    }
                    var dataUri = element.getAttribute('data-uri');
                    if (dataUri) {
                        return attributeSelector('data-uri', dataUri);
                    }
                    var chaucerId = element.getAttribute('data-chaucer-element-id');
                    if (chaucerId) {
                        return attributeSelector('data-chaucer-element-id', chaucerId);
                    }
                    return null;
                }
                function isAnchorCandidate(element) {
                    var tag = (element.tagName || '').toLowerCase();
                    return tag === 'h1' ||
                        tag === 'h2' ||
                        tag === 'h3' ||
                        tag === 'h4' ||
                        tag === 'h5' ||
                        tag === 'h6' ||
                        tag === 'p' ||
                        tag === 'figure' ||
                        tag === 'figcaption' ||
                        tag === 'img' ||
                        tag === 'li' ||
                        tag === 'table' ||
                        tag === 'blockquote' ||
                        tag === 'aside';
                }
                function isMediaElement(element) {
                    var tag = (element.tagName || '').toLowerCase();
                    if (tag === 'iframe' || tag === 'video' || tag === 'audio' ||
                        tag === 'canvas' || tag === 'object' || tag === 'embed') {
                        return true;
                    }
                    var id = element.getAttribute('id') || '';
                    if (/^player[0-9]*$/i.test(id)) {
                        return true;
                    }
                    var className = String(element.getAttribute('class') || '');
                    if (/\b(vjs|video-js|__embedpearsonvideoplayer__)\b/i.test(className)) {
                        return true;
                    }
                    return !!(element.closest && element.closest('figure.video'));
                }
                function findAnchor(win) {
                    var doc = null;
                    try {
                        doc = win.document;
                    } catch (error) {
                        return null;
                    }
                    if (!doc) {
                        return null;
                    }
                    var element = null;
                    try {
                        element = selector ? doc.querySelector(selector) : null;
                    } catch (error) {
                        element = null;
                    }
                    if (element) {
                        return { element: element, win: win };
                    }
                    var frames = [];
                    try {
                        frames = doc.querySelectorAll('iframe');
                    } catch (error) {
                        frames = [];
                    }
                    for (var index = 0; index < frames.length; index += 1) {
                        var child = null;
                        try {
                            child = frames[index].contentWindow;
                        } catch (error) {
                            child = null;
                        }
                        if (!child) {
                            continue;
                        }
                        var found = findAnchor(child);
                        if (found) {
                            return found;
                        }
                    }
                    return null;
                }
                function rectInRoot(element, win) {
                    var rect = element.getBoundingClientRect();
                    var top = rect.top;
                    var left = rect.left;
                    while (win && win !== window) {
                        var frame = win.frameElement;
                        if (!frame) {
                            break;
                        }
                        var frameRect = frame.getBoundingClientRect();
                        top += frameRect.top;
                        left += frameRect.left;
                        win = win.parent;
                    }
                    return { top: top, left: left };
                }
                function isVisibleInRoot(element, win) {
                    var rect = rectInRoot(element, win);
                    var bounds = element.getBoundingClientRect();
                    return bounds.width > 0 &&
                        bounds.height > 0 &&
                        rect.top < window.innerHeight &&
                        rect.top + bounds.height > 0;
                }
                function stableAncestor(element) {
                    var current = element;
                    while (current && current.nodeType === 1) {
                        var tag = (current.tagName || '').toLowerCase();
                        if (tag === 'body' || tag === 'html') {
                            break;
                        }
                        var selector = stableSelector(current);
                        if (selector && isAnchorCandidate(current) && !isMediaElement(current)) {
                            return { element: current, selector: selector };
                        }
                        current = current.parentElement;
                    }
                    return null;
                }
                function stableVisibleAnchor(win) {
                    var doc = win.document;
                    var candidates = [];
                    try {
                        candidates = doc.querySelectorAll([
                            'h1[id]',
                            'h2[id]',
                            'h3[id]',
                            'h4[id]',
                            'h5[id]',
                            'h6[id]',
                            'p[id]',
                            'figure[id]',
                            'img[id]',
                            'li[id]',
                            'table[id]',
                            'blockquote[id]',
                            'aside[id]',
                            'h1[data-uri]',
                            'h2[data-uri]',
                            'h3[data-uri]',
                            'h4[data-uri]',
                            'h5[data-uri]',
                            'h6[data-uri]',
                            'p[data-uri]',
                            'figure[data-uri]',
                            'figcaption[data-uri]',
                            'img[data-uri]',
                            'li[data-uri]',
                            'table[data-uri]',
                            'blockquote[data-uri]',
                            'aside[data-uri]',
                            'h1[data-chaucer-element-id]',
                            'h2[data-chaucer-element-id]',
                            'h3[data-chaucer-element-id]',
                            'h4[data-chaucer-element-id]',
                            'h5[data-chaucer-element-id]',
                            'h6[data-chaucer-element-id]',
                            'p[data-chaucer-element-id]',
                            'figure[data-chaucer-element-id]',
                            'figcaption[data-chaucer-element-id]',
                            'img[data-chaucer-element-id]',
                            'li[data-chaucer-element-id]',
                            'table[data-chaucer-element-id]',
                            'blockquote[data-chaucer-element-id]',
                            'aside[data-chaucer-element-id]'
                        ].join(','));
                    } catch (error) {
                        candidates = [];
                    }
                    var bestInViewport = null;
                    var bestAboveViewport = null;
                    for (var index = 0; index < candidates.length; index += 1) {
                        var candidate = candidates[index];
                        if (!isAnchorCandidate(candidate) ||
                            isMediaElement(candidate) ||
                            !isVisibleInRoot(candidate, win)) {
                            continue;
                        }
                        var selector = stableSelector(candidate);
                        if (selector) {
                            var rect = rectInRoot(candidate, win);
                            var entry = { element: candidate, selector: selector, top: rect.top };
                            if (rect.top >= 0) {
                                if (!bestInViewport || rect.top < bestInViewport.top) {
                                    bestInViewport = entry;
                                }
                            } else if (!bestAboveViewport || rect.top > bestAboveViewport.top) {
                                bestAboveViewport = entry;
                            }
                        }
                    }
                    return bestInViewport || bestAboveViewport;
                }
                var found = findAnchor(window);
                if (!found) {
                    return null;
                }
                var stable = stableVisibleAnchor(found.win) || stableAncestor(found.element);
                if (!stable) {
                    return null;
                }
                var rect = rectInRoot(stable.element, found.win);
                var scroller = document.scrollingElement || document.documentElement || document.body;
                var scrollY = Math.round(window.scrollY || (scroller && scroller.scrollTop) || 0);
                var scrollX = Math.round(window.scrollX || (scroller && scroller.scrollLeft) || 0);
                var vertical = scrollY !== 0 || (scroller && scroller.scrollHeight > window.innerHeight);
                return JSON.stringify({
                    cssSelector: stable.selector,
                    elementTop: rect.top,
                    elementLeft: rect.left,
                    viewportWidth: window.innerWidth,
                    viewportHeight: window.innerHeight,
                    scrollX: scrollX,
                    scrollY: scrollY,
                    axis: vertical ? "vertical" : "horizontal"
                });
            })();
        """.trimIndent()
    }

    private fun viewportAnchorRestoreScript(anchor: EpubViewportAnchor): String {
        val selector = JSONObject.quote(anchor.cssSelector)
        val targetTop = anchor.elementTop.toJsNumber()
        return """
            (function () {
                var selector = $selector;
                var targetTop = $targetTop;
                function findAnchor(win) {
                    var doc = null;
                    try {
                        doc = win.document;
                    } catch (error) {
                        return null;
                    }
                    if (!doc) {
                        return null;
                    }
                    var element = null;
                    try {
                        element = selector ? doc.querySelector(selector) : null;
                    } catch (error) {
                        element = null;
                    }
                    if (element) {
                        return { element: element, win: win };
                    }
                    var frames = [];
                    try {
                        frames = doc.querySelectorAll('iframe');
                    } catch (error) {
                        frames = [];
                    }
                    for (var index = 0; index < frames.length; index += 1) {
                        var child = null;
                        try {
                            child = frames[index].contentWindow;
                        } catch (error) {
                            child = null;
                        }
                        if (!child) {
                            continue;
                        }
                        var found = findAnchor(child);
                        if (found) {
                            return found;
                        }
                    }
                    return null;
                }
                function rectInRoot(found) {
                    var rect = found.element.getBoundingClientRect();
                    var top = rect.top;
                    var left = rect.left;
                    var win = found.win;
                    while (win && win !== window) {
                        var frame = win.frameElement;
                        if (!frame) {
                            break;
                        }
                        var frameRect = frame.getBoundingClientRect();
                        top += frameRect.top;
                        left += frameRect.left;
                        win = win.parent;
                    }
                    return { top: top, left: left };
                }
                var found = findAnchor(window);
                if (!found) {
                    return JSON.stringify({
                        applied: false,
                        reason: "missingAnchor",
                        targetTop: targetTop
                    });
                }
                var scroller = document.scrollingElement || document.documentElement || document.body;
                var beforeRect = rectInRoot(found);
                var beforeScrollY = Math.round(window.scrollY || scroller.scrollTop || 0);
                var deltaY = beforeRect.top - targetTop;
                if (window.scrollBy) {
                    window.scrollBy(0, deltaY);
                } else if (scroller) {
                    scroller.scrollTop = Math.max(0, scroller.scrollTop + deltaY);
                }
                var afterRect = rectInRoot(found);
                return JSON.stringify({
                    applied: true,
                    reason: null,
                    beforeTop: beforeRect.top,
                    afterTop: afterRect.top,
                    targetTop: targetTop,
                    beforeScrollY: beforeScrollY,
                    afterScrollY: Math.round(window.scrollY || scroller.scrollTop || 0)
                });
            })();
        """.trimIndent()
    }

    private fun parseViewportAnchorRestoreResult(
        result: String?,
        fallbackTargetTop: Double,
    ): EpubViewportAnchorRestoreResult? {
        val payload = decodeJavascriptStringResult(result) ?: return null
        return runCatching {
            val json = JSONObject(payload)
            EpubViewportAnchorRestoreResult(
                applied = json.optBoolean("applied", false),
                reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
                beforeTop = json.optDoubleOrNull("beforeTop"),
                afterTop = json.optDoubleOrNull("afterTop"),
                targetTop = json.optDoubleOrNull("targetTop") ?: fallbackTargetTop,
                beforeScrollY = json.optIntOrNull("beforeScrollY"),
                afterScrollY = json.optIntOrNull("afterScrollY"),
            )
        }.getOrNull()
    }

    private fun decodeJavascriptStringResult(result: String?): String? {
        if (result == null || result == "null") {
            return null
        }
        return runCatching {
            JSONArray("[$result]").optString(0)
                .takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun Double.toJsNumber(): String =
        if (isFinite()) toString() else "0"

    private val r2PagerAdapter: R2PagerAdapter?
        get() = if (::resourcePager.isInitialized) {
            resourcePager.adapter as? R2PagerAdapter
        } else {
            null
        }

    private val currentReflowablePageFragment: R2EpubPageFragment? get() =
        currentFragment as? R2EpubPageFragment

    private val currentFragment: Fragment? get() =
        fragmentAt(resourcePager.currentItem)

    private fun fragmentAt(index: Int): Fragment? =
        r2PagerAdapter?.mFragments?.get(adapter.getItemId(index))

    /**
     * Returns the reflowable page fragment matching the given href, if it is already loaded in the
     * view pager.
     */
    private fun loadedFragmentForHref(href: Url): R2EpubPageFragment? {
        val adapter = r2PagerAdapter ?: return null
        adapter.mFragments.forEach { _, fragment ->
            val pageFragment = fragment as? R2EpubPageFragment ?: return@forEach
            val link = pageFragment.link ?: return@forEach
            if (link.url() == href) {
                return pageFragment
            }
        }
        return null
    }

    override val currentLocator: StateFlow<Locator> get() = _currentLocator
    private val _currentLocator = MutableStateFlow(
        initialLocator
            ?: requireNotNull(publication.locatorFromLink(this.readingOrder.first()))
    )

    /**
     * Returns the [Locator] to the first HTML *block* element that is visible on the screen, even
     * if it begins on previous screen pages.
     */
    @ExperimentalReadiumApi
    override suspend fun firstVisibleElementLocator(): Locator? {
        if (!::resourcePager.isInitialized) return null

        return when (viewModel.layout) {
            Layout.FIXED ->
                currentLocator.value

            Layout.REFLOWABLE, Layout.SCROLLED -> {
                val resource = readingOrder[resourcePager.currentItem]
                currentReflowablePageFragment?.webView?.findFirstVisibleLocator()
                    ?.copy(
                        href = resource.url(),
                        mediaType = resource.mediaType ?: MediaType.XHTML
                    )
            }
        }
    }

    /**
     * While scrolling we receive a lot of new current locations, so we use a coroutine job to
     * debounce the notification.
     */
    private var debounceLocationNotificationJob: Job? = null

    /**
     * Mapping between reading order hrefs and the table of contents title.
     */
    private val tableOfContentsTitleByHref: Map<Href, String> by lazy {
        fun fulfill(linkList: List<Link>): MutableMap<Href, String> {
            var result: MutableMap<Href, String> = mutableMapOf()

            for (link in linkList) {
                val title = link.title ?: ""

                if (title.isNotEmpty()) {
                    result[link.href] = title
                }

                val subResult = fulfill(link.children)

                result = (subResult + result) as MutableMap<Href, String>
            }

            return result
        }

        fulfill(publication.tableOfContents).toMap()
    }

    private fun notifyCurrentLocation() {
        // Make sure viewLifecycleOwner is accessible.
        view ?: return

        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)

            // We don't want to notify the current location if the navigator is still loading a
            // locator, to avoid notifying intermediate locations.
            if (currentReflowablePageFragment?.isLoaded?.value == false || state != State.Ready) {
                return@launch
            }

            val reflowableWebView = currentReflowablePageFragment?.webView
            val progression = reflowableWebView?.run {
                // The transition has stabilized, so we can ask the web view to refresh its current
                // item to reflect the current scroll position.
                updateCurrentItem()
                progression.coerceIn(0.0, 1.0)
            } ?: 0.0

            val link = when (val pageResource = adapter.getResource(resourcePager.currentItem)) {
                is PageResource.EpubFxl -> checkNotNull(
                    pageResource.leftLink ?: pageResource.rightLink
                )
                is PageResource.EpubReflowable -> pageResource.link
                else -> throw IllegalStateException(
                    "Expected EpubFxl or EpubReflowable page resources"
                )
            }
            val positionLocator = publication.positionsByResource[link.url()]?.let { positions ->
                val index = ceil(progression * (positions.size - 1)).toInt()
                positions.getOrNull(index)
            }

            val currentLocator = Locator(
                href = link.url(),
                mediaType = link.mediaType ?: MediaType.XHTML,
                title = tableOfContentsTitleByHref[link.href] ?: positionLocator?.title ?: link.title,
                locations = (positionLocator?.locations ?: Locator.Locations()).copy(
                    progression = progression
                ),
                text = positionLocator?.text ?: Locator.Text()
            )

            _currentLocator.value = currentLocator

            // Deprecated notifications
            reflowableWebView?.let {
                paginationListener?.onPageChanged(
                    pageIndex = it.mCurItem,
                    totalPages = it.numPages,
                    locator = currentLocator
                )
            }
        }
    }

    public companion object {

        /**
         * Creates a factory for a dummy [EpubNavigatorFragment].
         *
         * Used when Android restore the [EpubNavigatorFragment] after the process was killed. You
         * need to make sure the fragment is removed from the screen before [onResume] is called.
         */
        public fun createDummyFactory(): FragmentFactory = createFragmentFactory {
            EpubNavigatorFragment(
                publication = dummyPublication,
                initialLocator = Locator(href = Url("#")!!, mediaType = MediaType.XHTML),
                readingOrder = null,
                initialPreferences = EpubPreferences(),
                listener = null,
                paginationListener = null,
                layout = Layout.REFLOWABLE,
                defaults = EpubDefaults(),
                configuration = Configuration()
            )
        }

        /**
         * Returns a URL to the application asset at [path], served in the web views.
         *
         * Returns null if the given [path] is not valid or an absolute URL.
         */
        public fun assetUrl(path: String): Url? =
            WebViewServer.assetUrl(path)
    }
}

@ExperimentalReadiumApi
private val EpubSettings.effectiveBackgroundColor: Int get() =
    backgroundColor?.int ?: theme.backgroundColor
