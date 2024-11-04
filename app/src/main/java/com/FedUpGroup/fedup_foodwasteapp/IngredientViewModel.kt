package com.FedUpGroup.fedup_foodwasteapp

import android.app.Application
import android.widget.TextView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import android.util.Log

// The IngredientViewModel class extends AndroidViewModel, providing the application context.
// It serves as a bridge between the UI and the repository, holding the app's data in a lifecycle-aware way.
class IngredientViewModel(application: Application) : AndroidViewModel(application) {

    private val _dataState = MutableLiveData<DataResult<List<Ingredient>>>()
    val dataState: LiveData<DataResult<List<Ingredient>>> = _dataState
    private val ingredientDao: IngredientDao
    private val networkMonitor = NetworkMonitor(application)
    private val coroutineScope = viewModelScope
    private val repository: IngredientRepository
    val allIngredients: Flow<List<Ingredient>>
    private val _searchQuery = MutableStateFlow("")
    private val _filteredIngredients = MediatorLiveData<List<Ingredient>?>()
    val filteredIngredients: MediatorLiveData<List<Ingredient>?> get() = _filteredIngredients
    private val _insertResult = MutableLiveData<Boolean>()
    val insertResult: LiveData<Boolean> get() = _insertResult
    private val apiService = RetrofitClient.apiService
    private val authManager = AuthManager.getInstance()

    // LiveData for synchronization status
    private val _syncStatus = MutableLiveData<String>()
    val data = MutableLiveData<Ingredient?>()
    private var syncJob: Job? = null

    // Sync-related state
    private val syncMutex = Mutex()
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        //  val ingredientDao = AppDatabase.getDatabase(application).ingredientDao()
        val database = (application as FedUpFoodWaste).database
        ingredientDao = database.ingredientDao()
        repository = IngredientRepository(ingredientDao, apiService)
        allIngredients = repository.allIngredients
        _filteredIngredients.value = emptyList()
        setupNetworkMonitoring()
    }

    private fun setupNetworkMonitoring() {
        Log.d("IngredientViewModel", "Starting network monitoring")
        networkMonitor.startMonitoring()

        viewModelScope.launch {
            networkMonitor.isNetworkAvailable
                .debounce(300) // Wait 300ms for network state to stabilize
                .distinctUntilChanged() // Only react to actual state changes
                .collect { isAvailable ->
                    Log.d("IngredientViewModel", "Network availability changed: $isAvailable")
                    if (isAvailable) {
                        Log.d("IngredientViewModel", "Network available, syncing unsynced ingredients")
                        syncUnsyncedIngredients()
                    } else {
                        Log.d("IngredientViewModel", "Network unavailable, loading from Room for offline mode")
                        loadFromRoomOffline()
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("IngredientViewModel", "ViewModel is being cleared, stopping network monitoring")
        networkMonitor.stopMonitoring()
        // syncJob?.cancel()
    }

    fun observeIngredientChanges() {
        Log.d("IngredientViewModel", "Setting up real-time ingredient change listener in Firebase")
        observeIngredientChangesInFirebase()
    }

    fun fetchIngredientsFromFirebase() {
        Log.d("IngredientViewModel", "Fetching ingredients from Firebase with ID token")
        authManager.getIdToken { token, error ->
            if (token != null) {
                Log.d("IngredientViewModel", "Token retrieved, initiating API fetch for ingredients")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val ingredients = repository.fetchIngredientsFromApi(token)
                        if (ingredients != null) {
                            Log.d("IngredientViewModel", "Fetched ${ingredients.size} ingredients from API")
                            _filteredIngredients.postValue(ingredients)
                        } else {
                            Log.d("IngredientViewModel", "No ingredients fetched, setting empty list")
                            _filteredIngredients.postValue(emptyList())
                        }
                    } catch (e: Exception) {
                        Log.e("IngredientViewModel", "Error fetching ingredients from API", e)
                    }
                }
            } else {
                Log.e("IngredientViewModel", "Failed to retrieve ID token: $error")
            }
        }
    }

    fun observeIngredientChangesInFirebase() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            Log.d("IngredientViewModel", "Setting up Firebase listener for user: $userId")
            val ingredientsRef = FirebaseDatabase.getInstance().getReference("ingredients/$userId")

            // Listen for any changes in the ingredients
            ingredientsRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d("IngredientViewModel", "Firebase data change detected, refetching ingredients")
                    fetchIngredientsFromFirebase()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("IngredientViewModel", "Firebase data listener cancelled: ${error.message}")
                }
            })
        } else {
            Log.e("IngredientViewModel", "No user ID available for Firebase listener setup")
        }
    }

    // Delete ingredient by firebase_id
    fun deleteIngredientByFirebaseId(firebaseId: String) {
        viewModelScope.launch {
            val ingredient = repository.getIngredientByFirebaseId(firebaseId)
            if (ingredient != null) {
                repository.deleteIngredientByFirebaseId(firebaseId)
            }
        }
    }

    // Updates only the firebaseId in RoomDB without incrementing version
    fun updateFirebaseIdOnly(id: Long, firebaseId: String) {
        viewModelScope.launch {
            repository.updateFirebaseIdOnly(id, firebaseId)
        }
    }

    // Function to update an ingredient
    fun updateIngredientDetails(ingredient: Ingredient) {
        viewModelScope.launch {
            repository.updateIngredientDetails(ingredient)
        }
    }

    fun loadFromRoomOffline() {
        _filteredIngredients.addSource(ingredientDao.getAllIngredients()) { ingredients ->
            _filteredIngredients.value = ingredients

        }
    }

    suspend fun insertOffline(ingredient: Ingredient): Long {
        return withContext(Dispatchers.IO) {
            repository.insertIngredient(ingredient)
        }
    }

    fun syncUnsyncedIngredients() {
        viewModelScope.launch {
            if (!syncMutex.tryLock()) {
                return@launch
            }

            try {
                _isSyncing.value = true

                val unsyncedIngredients = withContext(Dispatchers.IO) {
                    repository.getUnsyncedIngredients()
                }

                // Process each ingredient sequentially to avoid race conditions
                unsyncedIngredients.forEach { ingredient ->
                    try {
                        // Double-check sync status before processing
                        val currentIngredient = repository.getIngredientById(ingredient.id)
                        if (currentIngredient == null || currentIngredient.isSynced) {
                            return@forEach
                        }

                        when {
                            ingredient.isDeleted -> handleDeletedIngredient(ingredient)
                            ingredient.firebaseId.isEmpty() -> handleNewIngredient(ingredient)
                            else -> handleExistingIngredient(ingredient)
                        }
                    } catch (e: Exception) {
                    }
                }

            } finally {
                _isSyncing.value = false
                syncMutex.unlock()
            }
        }
    }

    private suspend fun handleDeletedIngredient(ingredient: Ingredient) {
        try {
            // If the ingredient has no Firebase ID, it was created offline and then deleted
            // We can just remove it from Room without syncing to Firebase
            if (ingredient.firebaseId.isEmpty()) {
                repository.hardDeleteIngredient(ingredient)
                return
            }

            // Otherwise, try to delete from Firebase
            val deleteResponse = repository.deleteIngredientFromFirebase(ingredient)
            if (deleteResponse.isSuccessful) {
                // Remove from local database after successful Firebase deletion
                repository.hardDeleteIngredient(ingredient)
            } else {
                // If deletion from Firebase fails, keep the soft delete mark but update sync status
                ingredient.apply {
                    isSynced = false  // Mark for retry
                    lastModified = System.currentTimeMillis()
                }
                repository.updateIngredient(ingredient)
            }
        } catch (e: Exception) {
            // In case of error, keep the soft delete mark but update sync status
            ingredient.apply {
                isSynced = false  // Mark for retry
                lastModified = System.currentTimeMillis()
            }
            repository.updateIngredient(ingredient)
        }
    }

    fun SoftDeleteIngredient(ingredient: Ingredient) {
        viewModelScope.launch {
            try {
                repository.softDeleteIngredient(ingredient)
                // If we're online, trigger sync immediately
                if (networkMonitor.isNetworkAvailable.value) {
                    syncUnsyncedIngredients()
                }
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun handleNewIngredient(ingredient: Ingredient) {
        try {
            // First, check if this ingredient has already been synced
            if (ingredient.isSynced) {
                return
            }

            // Mark as being processed to prevent duplicate processing
            ingredient.apply {
                isSynced = true
                lastModified = System.currentTimeMillis()
            }
            repository.updateIngredient(ingredient)

            val addResponse = repository.addIngredientToFirebase(ingredient)
            if (addResponse.isSuccessful) {
                addResponse.body()?.let { createdIngredient ->
                    // Update with Firebase ID and confirm sync
                    ingredient.apply {
                        firebaseId = createdIngredient.firebaseId
                    }
                    repository.updateIngredient(ingredient)
                } ?: run {
                    // If response body is null, mark as unsynced to retry later
                    ingredient.isSynced = false
                    repository.updateIngredient(ingredient)
                }
            } else {
                // If sync failed, mark as unsynced to retry later
                ingredient.isSynced = false
                repository.updateIngredient(ingredient)
            }
        } catch (e: Exception) {
            // In case of any exception, mark as unsynced to retry later
            ingredient.isSynced = false
            repository.updateIngredient(ingredient)
        }
    }

    private suspend fun handleExistingIngredient(ingredient: Ingredient) {
        val firebaseIngredient = getIngredientFromFirebase(ingredient)
        if (firebaseIngredient == null || ingredient.version > firebaseIngredient.version) {
            val updateResponse = repository.updateIngredientOnFirebase(ingredient)
            if (updateResponse.isSuccessful) {
                ingredient.apply {
                    isSynced = true
                    lastModified = System.currentTimeMillis()
                }
                repository.updateIngredient(ingredient)
            }
        }
    }

    private suspend fun getIngredientFromFirebase(ingredient: Ingredient): Ingredient? {
        return try {
            // Retrieve the token using getAuthToken()
            val token = getAuthToken()?.let { "Bearer $it" }

            if (token == null) {
                return null
            }

            val response = apiService.getIngredientById(ingredient.firebaseId)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    body
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getAuthToken(): String? = suspendCancellableCoroutine { continuation ->
        AuthManager.getInstance().getIdToken { token, error ->
            if (error != null) {
                continuation.resume(null) { }
            } else {
                continuation.resume(token) { }
            }
        }
    }

    fun fetchAndDisplayIngredientCounts(
        freshTextView: TextView,
        expiringSoonTextView: TextView,
        expiredTextView: TextView
    ) {
        viewModelScope.launch {
            try {
                val counts = repository.getIngredientCounts()
                withContext(Dispatchers.Main) {
                    freshTextView.text = counts.freshCount.toString()
                    expiringSoonTextView.text = counts.expiringSoonCount.toString()
                    expiredTextView.text = counts.expiredCount.toString()
                }
            } catch (_: Exception) {
            }
        }
    }

}


