package org.dcache.nearline;

import org.dcache.pool.nearline.spi.NearlineStorage;
import org.dcache.pool.nearline.spi.NearlineStorageProvider;

public class S3NearlineStorageProvider implements NearlineStorageProvider
{
    @Override
    public String getName()
    {
        return "org.dcache.nearline-s3";
    }

    @Override
    public String getDescription()
    {
        return "Enables communication to an S3-endpoint";
    }

    @Override
    public NearlineStorage createNearlineStorage(String type, String name)
    {
        return new S3NearlineStorage(type, name);
    }
}
