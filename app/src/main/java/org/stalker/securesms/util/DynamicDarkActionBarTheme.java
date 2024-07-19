package org.stalker.securesms.util;

import androidx.annotation.StyleRes;

import org.stalker.securesms.R;

public class DynamicDarkActionBarTheme extends DynamicTheme {

  protected @StyleRes int getTheme() {
    return R.style.Signal_DayNight_DarkActionBar;
  }
}
