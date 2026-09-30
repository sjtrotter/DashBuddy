package cloud.trotter.dashbuddy.core.pipeline.census

/**
 * The framework classes the census class check exempts (ADR-0011 §2; #1160 reviews AF2, AG2). Public so the
 * `:app` corpus guard can pin that the committed corpus renders no unlisted framework-prefixed class.
 */
object FrameworkClasses {
    /** The framework package prefixes the corpus guard audits against [KNOWN] (AG2). */
    val PACKAGES = listOf("android.", "androidx.", "com.google.android.material.")

    /**
     * The KNOWN framework classes the class check exempts (reviews AF2, AG2), by EXACT binary name: every
     * [PACKAGES] class the committed corpus renders (a corpus guard fails on an unlisted one),
     * every `AnonymousWrappers.WRAPPER_CLASSES` member (pinned by a test), and the Material `Chip` the
     * AG2 vectors name. Any other framework-PREFIXED class is judged like an app class — the prefix is
     * not proof (`androidx.RileyButton` is nulled beside "Riley").
     */
    val KNOWN: Set<String> = setOf(
        "android.appwidget.AppWidgetHostView",
        "android.view.SurfaceView",
        "android.view.TextureView",
        "android.view.View",
        "android.view.ViewGroup",
        "android.webkit.WebView",
        "android.widget.AutoCompleteTextView",
        "android.widget.Button",
        "android.widget.CheckBox",
        "android.widget.CompoundButton",
        "android.widget.EditText",
        "android.widget.FrameLayout",
        "android.widget.GridView",
        "android.widget.HorizontalScrollView",
        "android.widget.Image",
        "android.widget.ImageButton",
        "android.widget.ImageView",
        "android.widget.LinearLayout",
        "android.widget.ListView",
        "android.widget.ProgressBar",
        "android.widget.RadioButton",
        "android.widget.RelativeLayout",
        "android.widget.ScrollView",
        "android.widget.SeekBar",
        "android.widget.Switch",
        "android.widget.TextSwitcher",
        "android.widget.TextView",
        "android.widget.ViewSwitcher",
        "androidx.appcompat.widget.LinearLayoutCompat",
        "androidx.cardview.widget.CardView",
        "androidx.compose.ui.platform.ComposeView",
        "androidx.compose.ui.viewinterop.ViewFactoryHolder",
        "androidx.recyclerview.widget.RecyclerView",
        "androidx.recyclerview.widget.StaggeredGridLayoutManager",
        "androidx.viewpager.widget.ViewPager",
        "com.google.android.material.chip.Chip",
    )
}
