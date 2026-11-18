P=/data/data/com.android.mms/shared_prefs/com.android.mms_preferences.xml
K=pref_key_popup_notification
am force-stop com.android.mms
O=$(stat -c %u:%g /data/data/com.android.mms)
if [ ! -f "$P" ]; then
  mkdir -p /data/data/com.android.mms/shared_prefs
  printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n" > "$P"
fi
if grep -q "name=\"$K\"" "$P"; then
  sed -i "s#<boolean name=\"$K\" value=\"[a-z]*\" />#<boolean name=\"$K\" value=\"false\" />#" "$P"
else
  sed -i "s#</map>#    <boolean name=\"$K\" value=\"false\" />\n</map>#" "$P"
fi
chown -R "$O" /data/data/com.android.mms/shared_prefs
chmod 660 "$P"; restorecon -R /data/data/com.android.mms/shared_prefs 2>/dev/null
grep "$K" "$P"
