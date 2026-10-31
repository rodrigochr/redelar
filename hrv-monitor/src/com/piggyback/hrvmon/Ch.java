package com.piggyback.hrvmon;

/** Canais gravados a cada amostra. O índice é a posição no vetor de estado. */
final class Ch {
    static final int RPM = 0;
    static final int LAMBDA = 1;        // λ medido pela sonda 1 (banda larga)
    static final int LAMBDA_CMD = 2;    // λ que a ECU pediu
    static final int MAP = 3;           // kPa absoluto, melhor fonte disponível
    static final int BOOST = 4;         // bar relativo à pressão atmosférica
    static final int BOOST_CMD = 5;     // bar relativo, alvo da ECU (PID 0x70)
    static final int TIMING = 6;        // graus
    static final int RAIL = 7;          // bar
    static final int THROTTLE = 8;      // %
    static final int PEDAL = 9;         // %
    static final int LOAD = 10;         // %
    static final int IAT = 11;          // °C
    static final int ECT = 12;          // °C
    static final int STFT = 13;         // %
    static final int LTFT = 14;         // %
    static final int BARO = 15;         // kPa
    static final int ETHANOL = 16;      // %
    static final int SPEED = 17;        // km/h
    static final int MAF = 18;          // g/s
    // Fontes brutas de pressão do coletor; MAP e BOOST são derivados delas.
    static final int MAP_0B = 19;
    static final int MAP_87 = 20;
    static final int MAP_70 = 21;
    static final int BOOST_CMD_ABS = 22;
    static final int COUNT = 23;

    static final String[] CSV = {
        "rpm", "lambda", "lambda_cmd", "map_kpa", "boost_bar", "boost_cmd_bar", "timing_deg",
        "rail_bar", "throttle_pct", "pedal_pct", "load_pct", "iat_c", "ect_c", "stft_pct",
        "ltft_pct", "baro_kpa", "ethanol_pct", "speed_kmh", "maf_gs",
        "map_0b_kpa", "map_87_kpa", "map_70_kpa", "boost_cmd_abs_kpa",
    };

    private Ch() {}
}
