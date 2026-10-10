package eu.magicsk.transi.data.remote

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.util.Prefs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Named
import javax.inject.Singleton

/** Points a Magic API request at the developer override base URL; an empty or invalid override keeps it unchanged. */
fun magicApiUrl(original: HttpUrl, override: String?): HttpUrl {
    val base = override?.toHttpUrlOrNull() ?: return original
    return original.newBuilder()
        .scheme(base.scheme)
        .host(base.host)
        .port(base.port)
        .encodedPath(base.encodedPath.trimEnd('/') + original.encodedPath)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Singleton
    @Provides
    fun provideRepository(
        api: ApiRequests,
        imhdApi: ImhdRequests,
        idsbkApi: IdsbkRequests,
        githubApi: GithubRequests
    ) = DataRepository(api, imhdApi, idsbkApi, githubApi)

    /** Client for every Magic API call; follows the developer API URL override without a restart. */
    @Singleton
    @Provides
    @Named("magic")
    fun provideMagicHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val prefs = Prefs.get(context)
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = magicApiUrl(request.url, prefs.getString(Prefs.MAGIC_API_URL_OVERRIDE, ""))
                chain.proceed(request.newBuilder().url(url).build())
            }
            .build()
    }

    @Singleton
    @Provides
    fun provideApi(@Named("magic") client: OkHttpClient): ApiRequests {
        return Retrofit.Builder()
            .client(client)
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
    fun provideIdsbkApi(): IdsbkRequests {
        return Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("https://api.idsbk.sk/mobile/v1/")
            .build()
            .create(IdsbkRequests::class.java)
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