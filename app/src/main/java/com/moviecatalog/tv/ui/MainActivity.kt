package com.moviecatalog.tv.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.view.View
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moviecatalog.tv.R
import com.moviecatalog.tv.data.Catalog
import com.moviecatalog.tv.data.Movie
import com.moviecatalog.tv.data.Prefs
import com.moviecatalog.tv.smb.SmbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MovieCatalog"
private val SORT_OPTIONS = listOf("IMDb score", "Name (A-Z)", "Release year")
private const val ALL_YEARS = "All Years"

/**
 * The home screen: a searchable, sortable, year-filterable grid of the movies actually on your
 * NAS (the "discovered"/not-owned ones from movies.json are left out - only playable movies show
 * here). NAS Settings is reached via the gear icon in the top-right corner, not a grid entry.
 *
 * This is a plain RecyclerView, not a Leanback BrowseSupportFragment/VerticalGridSupportFragment.
 * Both of those were tried first and repeatedly failed on real hardware and an emulator:
 * BrowseSupportFragment's row container always had correct bounds but never attached any row
 * content regardless of how/when the adapter was set (confirmed via UI Automator dumps), and
 * VerticalGridSupportFragment crashed inside Leanback's own ImageCardView inflation
 * ("You must supply a layout_width attribute"). A plain RecyclerView + GridLayoutManager sidesteps
 * both, at the cost of Leanback's built-in D-pad shadow/scale focus animations (the focus
 * highlight here is a plain background-color/border swap - see card_focus_background.xml).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var adapter: MovieGridAdapter
    private var allMovies: List<Movie> = emptyList()  // library + discovered, unfiltered
    private var query = ""
    private var sortIndex = 0
    private var selectedYear = ALL_YEARS
    private var yearsPopulated = false
    private lateinit var grid: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = Prefs(this)
        if (!prefs.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        adapter = MovieGridAdapter(
            onMovieClick = { movie -> startActivity(Intent(this, DetailsActivity::class.java).putExtra("movie", movie)) },
        )
        grid = findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = this@MainActivity.adapter
        }

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val searchBox = findViewById<EditText>(R.id.search)
        val clearSearch = findViewById<ImageButton>(R.id.clear_search)

        // While browsing the grid with the D-pad, back would otherwise exit the app straight away -
        // send focus back to the search bar first instead, matching how "back" feels like going up
        // a level rather than a dead end. Once focus is already back on the search bar (or anywhere
        // else outside the grid), back behaves normally and exits.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val focused = currentFocus
                if (focused != null && grid.findContainingViewHolder(focused) != null) {
                    searchBox.requestFocus()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase() ?: ""
                clearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                applyFilterAndSort()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        clearSearch.setOnClickListener { searchBox.setText("") }

        findViewById<Spinner>(R.id.sort).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, SORT_OPTIONS)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    sortIndex = position
                    applyFilterAndSort()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        findViewById<Spinner>(R.id.year).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedYear = parent?.getItemAtPosition(position) as? String ?: ALL_YEARS
                // Picking a year is a "browse" action - an old search term left over would silently
                // keep filtering the results down further, which is confusing, so start it fresh.
                if (searchBox.text.isNotEmpty()) searchBox.setText("") else applyFilterAndSort()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        // Returning to the app (e.g. after backgrounding it, or coming back from Details/Playback)
        // was otherwise landing focus on the search bar every time, popping the keyboard up
        // unexpectedly - Android's default focus restoration picks the first focusable view when
        // nothing is focused, which is the search box since it comes before the grid in the layout.
        // Sending focus to the grid instead keeps (or defaults to) a movie card, which is what
        // "coming back to where you were browsing" should feel like.
        if (grid.childCount > 0 && currentFocus?.let { grid.findContainingViewHolder(it) } == null) {
            grid.requestFocus()
        }
    }

    private fun loadCatalog() {
        val prefs = Prefs(this)
        val client = SmbClient(prefs)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val catalog = withContext(Dispatchers.IO) { Catalog.parse(client.fetchCatalogJson()) }
                render(catalog)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load catalog", e)
                Toast.makeText(this@MainActivity, "Couldn't load movies.json from the NAS: ${e.message}. Opening Settings.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
    }

    private fun render(catalog: Catalog) {
        Log.d(TAG, "render(): ${catalog.movies.size} movies")
        // The TV app only plays movies actually on the NAS, so the "discovered" (not-owned) ones
        // from movies.json are left out here - unlike movies.html, which shows them for browsing.
        allMovies = catalog.movies
        populateYearSpinner()
        applyFilterAndSort()
    }

    /** Builds the year dropdown from the years actually present, defaulting to the current year. */
    private fun populateYearSpinner() {
        if (yearsPopulated) return  // only build this once per catalog load
        yearsPopulated = true

        val years = allMovies.mapNotNull { it.year.toIntOrNull() }.distinct().sortedDescending()
        val options = ArrayList<String>()
        options.add(ALL_YEARS)
        years.forEach { options.add(it.toString()) }

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        selectedYear = if (currentYear in options) currentYear else ALL_YEARS

        findViewById<Spinner>(R.id.year).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(options.indexOf(selectedYear), false)
        }
    }

    private fun applyFilterAndSort() {
        var list = if (query.isEmpty()) allMovies else allMovies.filter { it.name.lowercase().contains(query) }
        // The year filter only applies when browsing, not while actively searching - otherwise
        // finding a specific movie means remembering to switch to "All Years" first every time.
        if (query.isEmpty() && selectedYear != ALL_YEARS) list = list.filter { it.year == selectedYear }
        list = when (sortIndex) {
            1 -> list.sortedBy { it.name.lowercase() }
            2 -> list.sortedByDescending { it.year }
            else -> list.sortedByDescending { it.imdb?.toFloatOrNull() ?: -1f }
        }
        adapter.setItems(list)
    }
}
