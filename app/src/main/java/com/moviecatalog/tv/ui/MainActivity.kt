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
private val SORT_OPTIONS = listOf("IMDb", "Name", "Date")
private const val ALL_YEARS = "All Years"
private const val ALL_COUNTRIES = "Countries"
private const val ALL_GENRES = "Genres"

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
    private var selectedCountry = ALL_COUNTRIES
    private var selectedGenre = ALL_GENRES
    private var filtersPopulated = false
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
            layoutManager = GridLayoutManager(this@MainActivity, 4)
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
                val hasQuery = query.isNotEmpty()
                clearSearch.visibility = if (hasQuery) View.VISIBLE else View.GONE
                // The clear button only exists (as a focus target) while it's actually shown -
                // otherwise pressing right from the search box should go straight to the sort
                // spinner, not land on a hidden, stale focus target.
                searchBox.nextFocusRightId = if (hasQuery) R.id.clear_search else R.id.sort
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

        // Picking a year/country/genre is a "browse" action - an old search term left over would
        // silently keep filtering the results down further, which is confusing, so start it fresh.
        findViewById<Spinner>(R.id.year).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedYear = parent?.getItemAtPosition(position) as? String ?: ALL_YEARS
                if (searchBox.text.isNotEmpty()) searchBox.setText("") else applyFilterAndSort()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Spinner>(R.id.country).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedCountry = parent?.getItemAtPosition(position) as? String ?: ALL_COUNTRIES
                if (searchBox.text.isNotEmpty()) searchBox.setText("") else applyFilterAndSort()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Spinner>(R.id.genre).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedGenre = parent?.getItemAtPosition(position) as? String ?: ALL_GENRES
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
        populateFilterSpinners()
        applyFilterAndSort()
    }

    /** Builds the year/country/genre dropdowns from what's actually present in the catalog; year
     * defaults to the current year (falling back to "All Years" when nothing matches it). */
    private fun populateFilterSpinners() {
        if (filtersPopulated) return  // only build this once per catalog load
        filtersPopulated = true

        val years = allMovies.mapNotNull { it.year.toIntOrNull() }.distinct().sortedDescending()
        val yearOptions = ArrayList<String>()
        yearOptions.add(ALL_YEARS)
        years.forEach { yearOptions.add(it.toString()) }

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        selectedYear = if (currentYear in yearOptions) currentYear else ALL_YEARS

        findViewById<Spinner>(R.id.year).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, yearOptions)
            setSelection(yearOptions.indexOf(selectedYear), false)
        }

        // A movie can list several countries (co-productions) in one comma-separated field, so each
        // one gets its own selectable entry.
        val countries = allMovies.flatMap { it.country?.split(",")?.map(String::trim) ?: emptyList() }
            .filter { it.isNotEmpty() }.distinct().sorted()
        findViewById<Spinner>(R.id.country).adapter =
            ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf(ALL_COUNTRIES) + countries)

        val genres = allMovies.flatMap { it.genres }.distinct().sorted()
        findViewById<Spinner>(R.id.genre).adapter =
            ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf(ALL_GENRES) + genres)
    }

    private fun applyFilterAndSort() {
        var list = if (query.isEmpty()) allMovies else allMovies.filter { it.name.lowercase().contains(query) }
        // The year/country/genre filters only apply when browsing, not while actively searching -
        // otherwise finding a specific movie means remembering to reset them all to "All" first.
        if (query.isEmpty()) {
            if (selectedYear != ALL_YEARS) list = list.filter { it.year == selectedYear }
            if (selectedCountry != ALL_COUNTRIES) {
                list = list.filter { movie -> movie.country?.split(",")?.map(String::trim)?.contains(selectedCountry) == true }
            }
            if (selectedGenre != ALL_GENRES) list = list.filter { it.genres.contains(selectedGenre) }
        }
        list = when (sortIndex) {
            1 -> list.sortedBy { it.name.lowercase() }
            2 -> list.sortedByDescending { it.released?.takeIf { r -> r.isNotEmpty() } ?: (it.year + "-00-00") }
            else -> list.sortedByDescending { it.imdb?.toFloatOrNull() ?: -1f }
        }
        adapter.setItems(list)
    }
}
