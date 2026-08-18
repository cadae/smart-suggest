package com.lukecao.suggest.widget

/**
 * The 4x2 provider, and the original one.
 *
 * Deliberately still called this rather than `SuggestWidgetReceiver4x2` to match its
 * siblings in [Widgets]. A placed widget is bound to its provider's class name, so
 * renaming this would take the widget already on the home screen with it — and the
 * consistency would not be worth having to place it again.
 */
class SuggestWidgetReceiver : SuggestReceiver() {
    override val glanceAppWidget = SuggestWidget4x2()
}
