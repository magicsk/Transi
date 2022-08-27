package eu.magicsk.transi.data.remote

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.magicsk.transi.repository.DataRepository
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Singleton
    @Provides
    fun provideRepository(
        api: ApiRequests,
        imhdApi: ImhdRequests,
        githubApi: GithubRequests
    ) = DataRepository(api, imhdApi, githubApi)

    @Singleton
    @Provides
    fun provideApi(): ApiRequests {
        return Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("https://api.magicsk.eu/")
            .build()
            .create(ApiRequests::class.java)
    }

    @Singleton
    @Provides
    fun provideImhdApi(): ImhdRequests {
        return Retrofit.Builder()
            .addConverterFactory(ScalarsConverterFactory.create())
            .baseUrl("https://imhd.sk/ba/api/")
            .build()
            .create(ImhdRequests::class.java)
    }

    @Singleton
    @Provides
    fun provideGithubApi(): GithubRequests {
        return Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("https://api.github.com/")
            .build()
            .create(GithubRequests::class.java)
    }
}