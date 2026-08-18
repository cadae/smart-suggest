# R8 is on for release builds. Almost nothing here is a keep rule, because almost
# nothing in this app is reached by name:
#
#  - every activity, receiver and service is declared in the manifest, and AGP
#    generates keeps for those automatically;
#  - Compose, Glance and WorkManager all ship consumer rules inside their own AARs;
#  - so do the ads SDK, Play Billing and the consent SDK, which is worth stating because
#    hand-written billing keeps are a common bit of copied advice. Checked, not assumed:
#    billing-9.1.0 keeps com.android.vending.billing.**, keepnames both ProxyBillingActivity
#    classes and keeps the fields of its protobuf superclass; play-services-ads keeps
#    ClientApi and the mediation adapter interfaces; user-messaging-platform keeps its own
#    protobuf fields. Restating any of that here would only risk it going stale against
#    the AAR, which is the version that actually applies;
#  - the database is raw SQLite with column names written out as strings, so there is
#    no schema to reflect over.
#
# What is left is the handful of classes something else instantiates for us.

# WorkManager constructs a Worker reflectively from the class name it stored in its
# own database when the request was enqueued. That name is a string on disk from a
# previous version of the app, so renaming the class breaks a period already
# scheduled — the widget then stops refreshing until something re-enqueues it, which
# is exactly the failure this app exists to avoid. Keeping the constructor is not
# enough; the name has to survive too.
-keep class com.lukecao.suggest.work.RefreshWorker { *; }

# Glance resolves a widget's placed instances by the exact class of its
# GlanceAppWidget, and the AppWidgetProviderInfo the launcher holds for an already
# placed widget names the receiver. Both are manifest components and so already kept,
# but the mapping is by name in the launcher's own storage rather than in ours, and a
# rename deletes the user's widget rather than failing loudly. Stated explicitly
# because that consequence is not obvious from the manifest keep.
-keep class com.lukecao.suggest.widget.SuggestWidgetReceiver* { *; }

# Obfuscation buys nothing here — there is no license check, no API key, and no
# network call to protect — and it costs a readable stack trace in a Play Console
# crash report, which is the only view into a crash on somebody else's phone.
# Shrinking still happens; only the renaming is off.
-dontobfuscate
