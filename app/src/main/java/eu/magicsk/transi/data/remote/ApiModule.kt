package eu.magicsk.transi.data.remote

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.magicsk.transi.repository.DataRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Singleton
    @Provides
    fun provideRepository(
        api: ApiRequests
    ) = DataRepository(api)

    @Singleton
    @Provides
    fun provideApi(): ApiRequests {
        return Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("https://api.magicsk.eu/")
            .build()
            .create(ApiRequests::class.java)
    }
}