package prepare.edt.orchestrator.adapter;

import prepare.edt.orchestrator.entity.FlowCatalogue;

/** Looks the Flow up in the in-code catalogue ({@link FlowCatalogue}). A name that is not in it is a normal result, not a failure. */
public final class FlowCatalogueActivityImpl implements FlowCatalogueActivity {

    @Override
    public Lookup lookup(String flow) {
        return FlowCatalogue.find(flow).map(found -> new Lookup(true, found)).orElse(new Lookup(false, null));
    }
}
