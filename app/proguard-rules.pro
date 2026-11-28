# BookMriya: R8 rules.
# OkHttp, Coil, Compose and Coroutines ship their own consumer rules;
# here is only what R8 cannot see on its own.

# ProxyMode is read from SharedPreferences by name
# (SettingsStore: ProxyMode.valueOf). Keep the enum constants.
-keepclassmembers enum app.allbooks.net.ProxyMode {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
