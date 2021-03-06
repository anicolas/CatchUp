/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.ui.activity

import android.app.Activity
import android.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.preference.PreferenceFragment
import android.preference.PreferenceScreen
import android.support.design.widget.Snackbar
import android.support.v4.app.NavUtils
import android.support.v7.widget.Toolbar
import android.view.MenuItem
import android.widget.Toast
import butterknife.BindView
import com.google.firebase.perf.FirebasePerformance
import com.uber.autodispose.kotlin.autoDisposeWith
import dagger.Binds
import dagger.Module
import dagger.android.AndroidInjection
import dagger.android.AndroidInjector
import dagger.android.ContributesAndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasFragmentInjector
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.sweers.catchup.P
import io.sweers.catchup.R
import io.sweers.catchup.data.CatchUpDatabase
import io.sweers.catchup.injection.scopes.PerActivity
import io.sweers.catchup.injection.scopes.PerFragment
import io.sweers.catchup.ui.about.AboutActivity
import io.sweers.catchup.ui.base.BaseActivity
import io.sweers.catchup.util.clearCache
import io.sweers.catchup.util.format
import io.sweers.catchup.util.isInNightMode
import io.sweers.catchup.util.setLightStatusBar
import io.sweers.catchup.util.updateNightMode
import okhttp3.Cache
import java.io.File
import javax.inject.Inject

class SettingsActivity : BaseActivity(), HasFragmentInjector {

  companion object {
    const val SETTINGS_RESULT_DATA = 100
    const val NIGHT_MODE_UPDATED = "nightModeUpdated"
    const val NAV_COLOR_UPDATED = "navColorUpdated"
    const val ARG_FROM_RECREATE = "fromRecreate"
  }

  @Inject internal lateinit var dispatchingFragmentInjector: DispatchingAndroidInjector<Fragment>
  @BindView(R.id.toolbar) lateinit var toolbar: Toolbar

  /**
   * Backpress hijacks activity result codes, so store ours here in case
   */
  private val resultData = Bundle()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val viewGroup = viewContainer.forActivity(this)
    layoutInflater.inflate(R.layout.activity_settings, viewGroup)
    SettingsActivity_ViewBinding(this)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    if (!isInNightMode()) {
      toolbar.setLightStatusBar()
    }

    if (savedInstanceState == null) {
      fragmentManager.beginTransaction()
          .add(R.id.container, SettingsFrag())
          .commit()
    } else if (savedInstanceState.getBoolean(ARG_FROM_RECREATE, false)) {
      resultData.putBoolean(NIGHT_MODE_UPDATED, true)
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      if (!resultData.isEmpty) {
        setResult(SETTINGS_RESULT_DATA, Intent().putExtras(resultData))
      }
      NavUtils.navigateUpFromSameTask(this)
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(ARG_FROM_RECREATE, true)
  }

  override fun onBackPressed() {
    if (!resultData.isEmpty) {
      setResult(SETTINGS_RESULT_DATA, Intent().putExtras(resultData))
    }
    super.onBackPressed()
  }

  override fun fragmentInjector(): AndroidInjector<Fragment> = dispatchingFragmentInjector

  @Module
  abstract class SettingsActivityModule {
    @Binds
    @PerActivity
    abstract fun provideActivity(activity: SettingsActivity): Activity
  }

  class SettingsFrag : PreferenceFragment() {

    @Inject lateinit var cache: dagger.Lazy<Cache>
    @Inject lateinit var database: CatchUpDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
      AndroidInjection.inject(this)
      super.onCreate(savedInstanceState)
      addPreferencesFromResource(R.xml.prefs_general)

      (findPreference(
          P.SmartlinkingGlobal.KEY) as CheckBoxPreference).isChecked = P.SmartlinkingGlobal.get()
      (findPreference(P.DaynightAuto.KEY) as CheckBoxPreference).isChecked = P.DaynightAuto.get()
      (findPreference(P.DaynightNight.KEY) as CheckBoxPreference).isChecked = P.DaynightNight.get()
      (findPreference(P.Reports.KEY) as CheckBoxPreference).isChecked = P.Reports.get()

      val themeNavBarPref = findPreference(P.ThemeNavigationBar.KEY) as CheckBoxPreference
      themeNavBarPref.isChecked = P.ThemeNavigationBar.get()
    }

    override fun onPreferenceTreeClick(preferenceScreen: PreferenceScreen,
        preference: Preference): Boolean {
      when (preference.key) {
        P.SmartlinkingGlobal.KEY -> {
          P.SmartlinkingGlobal.put((preference as CheckBoxPreference).isChecked).apply()
          return true
        }
        P.DaynightAuto.KEY -> {
          val isChecked = (preference as CheckBoxPreference).isChecked
          P.DaynightAuto.put(isChecked)
              .apply {
                if (isChecked) {
                  // If we're enabling auto, clear out the prev daynight night-only mode
                  putBoolean(P.DaynightNight.KEY, false)
                  (findPreference(P.DaynightNight.KEY) as CheckBoxPreference).isChecked = false
                }
              }
              .apply()
          activity.updateNightMode()
          return true
        }
        P.DaynightNight.KEY -> {
          P.DaynightNight.put((preference as CheckBoxPreference).isChecked).apply()
          activity.updateNightMode()
          return true
        }
        P.ThemeNavigationBar.KEY -> {
          P.ThemeNavigationBar.put((preference as CheckBoxPreference).isChecked).apply()
          (activity as SettingsActivity).resultData.putBoolean(NAV_COLOR_UPDATED, true)
          return true
        }
        P.Reports.KEY -> {
          val isChecked = (preference as CheckBoxPreference).isChecked
          FirebasePerformance.getInstance().isPerformanceCollectionEnabled = isChecked
          P.Reports.put(isChecked).apply()
          Snackbar.make(view, R.string.settings_reset, Snackbar.LENGTH_SHORT)
              .setAction(R.string.undo) {
                // TODO Maybe this should actually be a restart button
                P.Reports.put(!isChecked).apply()
                preference.isChecked = !isChecked
              }
              .show()
          return true
        }
        P.ClearCache.KEY -> {
          Single.fromCallable {
            val cacheCleaned = activity.applicationContext.clearCache()
            val networkCacheCleaned = with(cache.get()) {
              val initialSize = size()
              evictAll()
              return@with initialSize - size()
            }
            val dbFile = File(database.openHelper.readableDatabase.path)
            val initialDbSize = dbFile.length()
            with(database.serviceDao()) {
              nukeItems()
              nukePages()
            }
            with(database.smmryDao()) {
              nukeItems()
            }
            val deletedFromDb = initialDbSize - dbFile.length()
            return@fromCallable cacheCleaned + deletedFromDb + networkCacheCleaned
          }.subscribeOn(Schedulers.io())
              .observeOn(AndroidSchedulers.mainThread())
              .autoDisposeWith(activity as BaseActivity)
              .subscribe { cleanedAmount, throwable ->
                // TODO Use jw's byte units lib, this isn't totally accurate
                val errorMessage = throwable?.let {
                  getString(R.string.settings_error_cleaning_cache)
                } ?: getString(R.string.clear_cache_success, cleanedAmount.format())
                Toast.makeText(activity, errorMessage, Toast.LENGTH_SHORT).show()
              }
          return true
        }
        P.About.KEY -> {
          startActivity(Intent(activity, AboutActivity::class.java))
          return true
        }
      }

      return super.onPreferenceTreeClick(preferenceScreen, preference)
    }

    @Module
    abstract class SettingsFragmentBindingModule {

      @PerFragment
      @ContributesAndroidInjector
      internal abstract fun settingsFragment(): SettingsFrag
    }
  }
}
