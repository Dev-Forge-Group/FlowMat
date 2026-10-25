package org.myweb.flowmat.domain.quality.application.publicapi;

/**
 * What another bounded context may read about a nonconformity: its number, its status (open, closed, cancelled), what is to
 * happen to the product ({@code disposition}, e.g. return_to_supplier) and the item and LOT it is about, null when none.
 */
public record NcrView(String nonconformityId, String projectId, String ncrNo, String status, String disposition, String itemId, String lotId) {
}
