package com.FedUpGroup.fedup_foodwasteapp

import android.util.Log
import androidx.lifecycle.LiveData

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import retrofit2.Response

class IngredientRepository(
    private val ingredientDao: IngredientDao,
    val apiService: ApiService, // Made apiService public for ViewModel access
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastSyncTime: Long = 0 // This variable will hold the last sync time

    // In your Repository
    val allIngredients: Flow<List<Ingredient>> = ingredientDao.getAllIngredientsWhereDeleted()

    suspend fun insertIngredient(ingredient: Ingredient): Long {
        return ingredientDao.insert(ingredient)
    }

    suspend fun getIngredientCounts(): IngredientCounts {
        return ingredientDao.getIngredientCounts()
    }

    suspend fun getIngredientById(id: Long): Ingredient? = withContext(Dispatchers.IO) {
        ingredientDao.getIngredientById(id)
    }
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    suspend fun getUnsyncedIngredients(): List<Ingredient> {
        return ingredientDao.getUnsyncedIngredients()
    }
    suspend fun addIngredientToFirebase(ingredient: Ingredient): Response<Ingredient> {
        Log.d("IngredientSync", "addIngredientToFirebase: $ingredient")

        return apiService.addIngredient(ingredient)

    }

    suspend fun deleteIngredientFromFirebase(ingredient: Ingredient): Response<Void> {
        return apiService.deleteIngredient(ingredient.firebaseId)
    }
    suspend fun updateIngredient(ingredient: Ingredient) {
        ingredientDao.update(ingredient) // Update ingredient in RoomDB
    }

    // Function to update an existing ingredient on Firebase
    suspend fun updateIngredientOnFirebase(ingredient: Ingredient): Response<Void> {
        // Ensure that the ingredient has a Firebase ID before trying to update
        if (ingredient.firebaseId.isEmpty()) {
            throw IllegalArgumentException("Firebase ID is missing for this ingredient.")
        }

        // Make the API call to update the ingredient
        return apiService.updateIngredient(
            firebaseId = ingredient.firebaseId,
            ingredient = ingredient
        )
    }

    suspend fun sendExpirationData(
        token: String,
        fcmToken: String,
        notificationData: Map<String, String>
    ) {
        try {
            apiService.sendExpirationData(token, fcmToken, notificationData)
        } catch (e: Exception) {
            throw e
        }
    }

    // Update only Firebase ID in RoomDB without incrementing version
    suspend fun updateFirebaseIdOnly(id: Long, firebaseId: String) {
        val existingIngredient = ingredientDao.getIngredientById(id)
        if (existingIngredient != null) {
            val ingredientWithFirebaseId = existingIngredient.copy(firebaseId = firebaseId)
            ingredientDao.updateIng(ingredientWithFirebaseId)
        }
    }

    suspend fun updateIngredientDetails(updatedIngredient: Ingredient): Boolean {
        // Look up the existing ingredient by Room `id` instead of `firebaseId`
        val existingIngredient = ingredientDao.getIngredientById(updatedIngredient.id)

        return if (existingIngredient != null) {
            // Ensure we're updating the existing record and incrementing its version
            val ingredientToUpdate = existingIngredient.copy(
                productName = updatedIngredient.productName,
                quantity = updatedIngredient.quantity,
                expirationDate = updatedIngredient.expirationDate,
                category = updatedIngredient.category,
                firebaseId = existingIngredient.firebaseId.ifEmpty { updatedIngredient.firebaseId },
                version = existingIngredient.version + 1, // Increment only on update
                lastModified = System.currentTimeMillis(),
                isSynced = false
            )

            // Update the ingredient in the Room database
            ingredientDao.updateIng(ingredientToUpdate) > 0
        } else {
            false // No ingredient found; nothing to update
        }
    }

    suspend fun deleteIngredientByFirebaseId(firebaseId: String) {
        ingredientDao.deleteByFirebaseId(firebaseId)
    }

    suspend fun getIngredientByFirebaseId(firebaseId: String): Ingredient? {
        return ingredientDao.getIngredientByFirebaseId(firebaseId)
    }

    suspend fun markIngredientsAsSynced(ingredients: List<Ingredient>) {
        ingredients.forEach { it.isSynced = true }
        ingredientDao.updateIngredients(ingredients)
    }

    fun syncIngredientsWithRoom(coroutineScope: CoroutineScope, ingredients: List<Ingredient>) {
        coroutineScope.launch(Dispatchers.IO) {
            ingredients.forEach { ingredient ->
                // Use the suspend function to get the local ingredient
                val localIngredient = ingredientDao.getIngredientByIdSuspend(ingredient.id)

                if (localIngredient == null) {
                    ingredientDao.insert(ingredient) // Insert new ingredient if not present in RoomDB
                } else if (localIngredient != ingredient) {
                    ingredientDao.update(ingredient) // Update if ingredient details differ
                }
            }
        }
    }

    // Fetch from REST API
    suspend fun fetchIngredientsFromApi(token: String): List<Ingredient>? {
        try {
            val response = apiService.getIngredients()
            return if (response.isSuccessful) {
                response.body()  // Return the ingredients list
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }

    // Add Ingredient to REST API
    suspend fun addIngredientToApi(ingredient: Ingredient) {
        try {
            val response = apiService.addIngredient(ingredient)
            if (response.isSuccessful) {
            } else {
            }
        } catch (e: Exception) {
        }
    }

    suspend fun softDeleteIngredient(ingredient: Ingredient) {
        ingredient.apply {
            isDeleted = true     // Mark for deletion
            isSynced = false     // Mark as unsynced so it will be processed when online
            lastModified = System.currentTimeMillis()
            version += 1         // Increment version
        }
        ingredientDao.update(ingredient)  // Update in Room
    }

    // This will be called after successful Firebase deletion
    suspend fun hardDeleteIngredient(ingredient: Ingredient) {
        ingredientDao.delete(ingredient)  // Actually remove from Room
    }

}