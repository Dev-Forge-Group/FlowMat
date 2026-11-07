package org.myweb.flowmat.domain.bom.application.publicapi;

import java.time.LocalDate;

/** An approved revision covering an explicit project calendar day; no server/browser date is inferred. */
public record EffectiveBomView(String bomId,String targetItemId,Integer bomVersion,LocalDate effectiveFrom,LocalDate effectiveTo) {}
