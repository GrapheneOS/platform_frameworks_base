package app.grapheneos.goscompat.securespawn;

interface INativeSignalProbe {
    oneway void terminateWithSigterm();
    int[] getSupplementaryGroups();
    int getPid();
}
