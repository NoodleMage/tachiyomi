package tachiyomi.util

import com.afollestad.aesthetic.Aesthetic
import tachiyomi.app.R

object ThemeUtil {

  fun setLightTheme() {
    Aesthetic.config {
      activityTheme(R.style.Theme_Light)
    }
  }

  fun setDarkTheme() {
    Aesthetic.config {
      activityTheme(R.style.Theme_Dark)
      isDark(true)
    }
  }

  fun setTachiyomiAccent() {
    Aesthetic.config {
      // ?colorPrimary, used for Toolbars, etc.
      colorPrimary(res = R.color.material_red_800)
      // ?colorPrimaryDark, used for status bars, etc.
      colorPrimaryDark(res = R.color.material_red_800)
      // ?colorAccent, used for input fields, buttons, etc.
      colorAccent(res = R.color.material_red_800)
    }
  }

}