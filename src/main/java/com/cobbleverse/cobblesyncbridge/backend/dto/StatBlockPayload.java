package com.cobbleverse.cobblesyncbridge.backend.dto;

public record StatBlockPayload(
        Integer hp,
        Integer atk,
        Integer def,
        Integer spa,
        Integer spd,
        Integer spe
) {}
