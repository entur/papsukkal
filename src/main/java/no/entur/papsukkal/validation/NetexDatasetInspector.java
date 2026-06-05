package no.entur.papsukkal.validation;

import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Streams a NeTEx fare-zone export with StAX and extracts the counts the validation gateway
 * cares about — O(1) memory over a multi-MB body, no JAXB/DOM unmarshal.
 *
 * <p>A single pass collects every {@code FareZone} {@code id} (FareFrame zone definitions),
 * counts {@code GroupOfTariffZones} elements (SiteFrame groups), and collects every group member
 * {@code TariffZoneRef} {@code ref}, then resolves each member ref against the FareZone id set.
 * A member's {@code ref} points at the FareZone id defined in the FareFrame (the element is named
 * {@code TariffZoneRef} only because {@code FareZone} is a NeTEx subtype of {@code TariffZone}).
 */
@Component
public class NetexDatasetInspector {

    private final XMLInputFactory factory;

    public NetexDatasetInspector() {
        this.factory = XMLInputFactory.newFactory();
        // Harden against XXE / entity-expansion: this input is external (Entur's GCS export).
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    /**
     * @throws XMLStreamException if the body is not well-formed XML; {@link DatasetValidator}
     *                            catches this and turns it into a validation failure.
     */
    public DatasetCounts inspect(InputStream body) throws XMLStreamException {
        Set<String> fareZoneIds = new HashSet<>();
        List<String> memberRefs = new ArrayList<>();
        int groupCount = 0;

        XMLStreamReader reader = factory.createXMLStreamReader(body);
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (reader.getLocalName()) {
                    case "FareZone" -> {
                        String id = reader.getAttributeValue(null, "id");
                        if (id != null) {
                            fareZoneIds.add(id);
                        }
                    }
                    case "GroupOfTariffZones" -> groupCount++;
                    case "TariffZoneRef" -> {
                        String ref = reader.getAttributeValue(null, "ref");
                        if (ref != null) {
                            memberRefs.add(ref);
                        }
                    }
                    default -> {
                        // not a counted element
                    }
                }
            }
        } finally {
            reader.close();
        }

        List<String> unresolved = memberRefs.stream()
                .filter(ref -> !fareZoneIds.contains(ref))
                .toList();

        return new DatasetCounts(fareZoneIds.size(), groupCount, memberRefs.size(), unresolved);
    }
}
