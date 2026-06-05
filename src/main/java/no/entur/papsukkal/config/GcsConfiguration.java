package no.entur.papsukkal.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.rutebanken.helper.gcp.repository.GcsBlobStoreRepository;
import org.rutebanken.helper.storage.repository.BlobStoreRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Entur {@code storage-gcp-gcs} helper as a {@link BlobStoreRepository} bean for the
 * state store. The {@link Storage} client is built with Application Default Credentials, which on
 * GKE resolves to the pod's Workload Identity service account — no credential files to manage.
 */
@Configuration
public class GcsConfiguration {

    @Bean
    public BlobStoreRepository syncStateBlobStoreRepository(GcsProperties props) {
        Storage storage = StorageOptions.newBuilder()
                .setProjectId(props.projectId())
                .build()
                .getService();
        GcsBlobStoreRepository repository = new GcsBlobStoreRepository(storage);
        repository.setContainerName(props.bucket());
        return repository;
    }
}
