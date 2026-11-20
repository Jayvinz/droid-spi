package demo;

import top.jayvin.android.spi.ServiceProvider;

@ServiceProvider(service = JavaApi.class)
public class JavaImpl implements JavaApi {
    @Override
    public String name() {
        return "java";
    }
}
