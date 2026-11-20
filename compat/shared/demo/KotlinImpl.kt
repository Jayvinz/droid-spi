package demo

import top.jayvin.android.spi.ServiceProvider

@ServiceProvider(service = KotlinApi::class)
class KotlinImpl : KotlinApi {
    override fun name() = "kotlin"
}
