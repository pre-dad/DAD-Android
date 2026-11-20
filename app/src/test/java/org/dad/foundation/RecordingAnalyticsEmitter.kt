package org.dad.foundation

/** Test emitter that records events in emission order. */
class RecordingAnalyticsEmitter : AnalyticsEmitter {
    private val _events = mutableListOf<AnalyticsEvent>()
    val events: List<AnalyticsEvent> get() = _events.toList()
    override fun emit(event: AnalyticsEvent) { _events.add(event) }
}
