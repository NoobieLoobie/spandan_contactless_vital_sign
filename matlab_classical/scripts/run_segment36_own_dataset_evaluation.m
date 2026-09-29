% RUN_SEGMENT36_OWN_DATASET_EVALUATION Segment 36: first-ever evaluation of
% this pipeline on the "Own Dataset" (9 subjects, real finger-PPG ground
% truth, AFE4404 device, recorded 2026-09-19) -- identified in
% docs/Segment35_Accuracy_Research_and_Plan.md (Test 2) but never run
% (that test was gated on the cancelled DL-model evaluation, Test 1).
%
% WHY THIS NEEDED NEW INFRASTRUCTURE FIRST: the videos are HEVC, 10-bit,
% color_transfer=arib-std-b67 (HLG/HDR) -- confirmed via direct ffprobe
% inspection and a direct MATLAB VideoReader test this session (decodes
% without erroring, but the landscape-named file's frames come out
% rotated 90 degrees, since VideoReader ignores its Display Matrix
% rotation flag, and the RGB values are an uncontrolled HDR->8-bit
% conversion, not a real HLG tone-map). io/ensureSDRVideo.m (new) fixes
% this transparently -- verified a no-op for every previously-validated
% UBFC/VIPL video, and verified correct (tone-mapped + auto-rotated) on
% these files -- and is now wired into io/loadUBFCVideo.m/loadVIPLVideo.m
% for every future caller, not just this script.
%
% SUBJECT<->GROUND-TRUTH MAPPING: two of the nine do NOT share a name
% between their video folder and their ground-truth CSV. Resolved by
% cross-matching each subject's own embedded capture timestamp (every
% subject has a "<name>_Volts_<timestamp>.csv" from the AFE4404 export
% software sitting NEXT TO their video, and the ground-truth summary's
% own source_file column carries the identical timestamp):
%   - "Device_test" (the ground-truth dataset's own README flags this as
%     "unlabeled recording, filename has no subject name, confirm whose
%     this is") has source_file Device_Volts_20260919_141054.csv --
%     IDENTICAL timestamp to Abrar/Normal/Abrar_Volts_20260919_141054.csv.
%     Resolved: Device_test = Abrar (Normal condition).
%   - "Lubaba_after_exercise" has source_file
%     Lubaba_after_exercise_Volts_20260919_150258.csv -- IDENTICAL
%     timestamp to Lubaba/After Breath Hold/
%     Lubaba_after_breath_hold_Volts_20260919_150258.csv. Resolved:
%     despite its own filename saying "after_exercise", this ground-truth
%     row is actually the AFTER-BREATH-HOLD condition, not exercise -- a
%     real labeling inconsistency in the ground-truth export, stated
%     plainly here rather than silently assumed either way.
% Ramis/After_exercise and Abrar/After_exercise have NO ground-truth
% counterpart at all (no timestamp match found) -- excluded, not guessed.
%
% METHOD: byte-for-byte the same protocol as
% scripts/run_segment35_mediapipe_anatomy_roi_batch.m's own UBFC Pool A
% loop (same functions, same shared-f0-from-baseline convention, same
% ABPF-only notch-confidence chain, reused verbatim) plus
% scripts/run_segment35_task_branch2_gt_correlation.m's GT-PPG correlation
% method (estimateLagPolarityByGroundTruth.m + z-score + Pearson r) --
% applied to baseline (roi/extractROISignals.m) vs the NOW-PROMOTED
% default anatomy ROI (roi/faceMeshAnatomyROIExtraction.m), so this
% script's own numbers are directly comparable to Segment 35's tables.
% maxLagSec is 15s here (vs Segment 13/35's 2s) because this dataset's
% video and PPG come from two independently-started, unsynchronized
% devices (a phone and a separate PPG logger), unlike UBFC's single
% hardware-synced capture -- the true start offset is unknown and could
% plausibly be several seconds, not sub-second.
%
% Does NOT modify roi/extractROISignals.m, roi/faceMeshAnatomyROIExtraction.m,
% morphology/*, pipeline/estimateVitalsAndMorphology.m, or any existing
% cached .mat/.csv file -- new, additive, read-only-on-the-pipeline script.
%
% Output: results/metrics/segment36_own_dataset_branch1_hr.csv,
%         results/metrics/segment36_own_dataset_branch2_gt_correlation.csv

pyenv('ExecutionMode', 'OutOfProcess');

thisFileDir = fileparts(mfilename('fullpath'));
projectRoot = fileparts(fileparts(thisFileDir));
addpath(genpath(fullfile(projectRoot, 'matlab', 'src')));

ownRoot = fullfile(fileparts(projectRoot), 'Own Dataset');
gtRoot = fullfile(ownRoot, 'Ground_Truth_PPG_All_Subjects', 'Ground_Truth_PPG_All_Subjects');
gtFullDir = fullfile(gtRoot, 'per_subject_csv');
summaryCsvPath = fullfile(gtRoot, 'SUMMARY_ground_truth_HR_SpO2.csv');

processedRoot = fullfile(projectRoot, 'data', 'processed');
metricsRoot = fullfile(projectRoot, 'results', 'metrics');
figuresRoot = fullfile(projectRoot, 'results', 'figures');
if ~isfolder(figuresRoot), mkdir(figuresRoot); end

% subjectID (namespaced OWN_ prefix, keeps out of UBFC/VIPL ID space),
% video path (the pre-rotated "_Vertical" sibling -- sidesteps relying on
% ensureSDRVideo's rotation detection for THIS dataset, though that path
% is independently verified working too), ground-truth full-recording CSV,
% ground-truth summary "subject" key, human-readable condition label.
subjects = {
    'OWN_Abrar',        fullfile(ownRoot, 'Abrar', 'Normal', 'Abrar_Vertical.MOV'),                              'Device_test_ground_truth_ppg_full.csv',              'Device_test',           'normal';
    'OWN_Bayezid',      fullfile(ownRoot, 'Bayezid', 'BayezidA_Vertical.MOV'),                                   'bayezid_below_ground_truth_ppg_full.csv',            'bayezid_below',         'normal';
    'OWN_Hadi',         fullfile(ownRoot, 'Hadi', 'Hadi_Vertical.MOV'),                                          'hadi_ground_truth_ppg_full.csv',                     'hadi',                  'normal';
    'OWN_Lubaba',       fullfile(ownRoot, 'Lubaba', 'Normal', 'Lubaba_Vertical.MOV'),                            'lubaba_left_ground_truth_ppg_full.csv',              'lubaba_left',           'normal';
    'OWN_Lubaba_ABH',   fullfile(ownRoot, 'Lubaba', 'After Breath Hold', 'Lubaba_After_breath_hold_Vertical.MOV'),'Lubaba_after_exercise_ground_truth_ppg_full.csv',    'Lubaba_after_exercise', 'after-breath-hold';
    'OWN_NayeemSir',    fullfile(ownRoot, 'Nayeem Sir', 'NayeemSir_Vertical.MOV'),                                'Nayeem_sir_ground_truth_ppg_full.csv',               'Nayeem_sir',            'normal';
    'OWN_Ramis',        fullfile(ownRoot, 'Ramis', 'Normal', 'Ramis_Vertical.MOV'),                               'ramis_right_ground_truth_ppg_full.csv',              'ramis_right',           'normal';
    'OWN_SelimSir',     fullfile(ownRoot, 'Selim Sir', 'Selim_Sir_Vertical.MOV'),                                 'selim_right_ground_truth_ppg_full.csv',              'selim_right',           'normal';
    'OWN_Siyam',        fullfile(ownRoot, 'Siyam', 'Siyam_Vertical.MOV'),                                        'Siyam_after_exercise_ground_truth_ppg_full.csv',     'Siyam_after_exercise',  'after-exercise';
};

maxLagSec = 15.0;
targetFsCorr = 250;

% [fix] Only write the header if the CSV doesn't already exist -- an
% earlier version of this script unconditionally overwrote both CSVs on
% every launch, silently wiping already-computed subject rows on a
% restart (this project's other resumable batch scripts, e.g.
% run_segment35_mediapipe_anatomy_roi_batch.m, all guard this the same
% way; this file originally missed it).
hrCsvPath = fullfile(metricsRoot, 'segment36_own_dataset_branch1_hr.csv');
hrHeader = "subjectID,condition,HR_gt_spectral,HR_gt_beatMean,HR_chrom_baseline,HR_chrom_anatomy,HR_pos_baseline,HR_pos_anatomy,HR_green_baseline,HR_green_anatomy,droppedFrames_anatomy,numFrames_anatomy,ibiCv";
if ~isfile(hrCsvPath), writelines(hrHeader, hrCsvPath); end

corrCsvPath = fullfile(metricsRoot, 'segment36_own_dataset_branch2_gt_correlation.csv');
corrHeader = "subjectID,condition,notchConf_baseline,notchConf_anatomy,corr_baseline,corr_anatomy,lagSec_baseline,lagSec_anatomy";
if ~isfile(corrCsvPath), writelines(corrHeader, corrCsvPath); end

for k = 1:size(subjects, 1)
    subjectID = subjects{k, 1};
    videoPath = subjects{k, 2};
    gtCsvName = subjects{k, 3};
    gtSubjectKey = subjects{k, 4};
    condition = subjects{k, 5};

    fprintf('\n--- Segment 36: %s (%s) ---\n', subjectID, condition);

    if ~isfile(videoPath)
        fprintf('  SKIP -- video not found: %s\n', videoPath);
        continue
    end

    gt = loadOwnDatasetGroundTruth(fullfile(gtFullDir, gtCsvName), summaryCsvPath, gtSubjectKey);

    % --- Baseline ROI (cached, resumable) ---
    baseCachePath = fullfile(processedRoot, [subjectID '_rgb_traces.mat']);
    if isfile(baseCachePath)
        c = load(baseCachePath);
        R_base = c.R; G_base = c.G; B_base = c.B; fsBase = c.fs;
    else
        [framesBase, fsBase, ~] = loadUBFCVideo(videoPath); % generic VideoReader wrapper, now HDR/rotation-safe
        [R_base, G_base, B_base, ~, ~, ~] = extractROISignals(framesBase, fsBase);
        R = R_base; G = G_base; B = B_base; fs = fsBase; %#ok<NASGU>
        save(baseCachePath, 'R', 'G', 'B', 'fs', '-v7');
    end

    % --- Anatomy ROI (cached, resumable) ---
    anCachePath = fullfile(processedRoot, [subjectID '_meshanatomy_rgb_traces.mat']);
    if isfile(anCachePath)
        ca = load(anCachePath);
        R_an = ca.R; G_an = ca.G; B_an = ca.B; fsAn = ca.fs;
    else
        [framesAn, fsAn, ~] = loadUBFCVideo(videoPath);
        [R_an, G_an, B_an, roiTimestampsAn, droppedFrameIdxAn, debugFrameAn] = faceMeshAnatomyROIExtraction(framesAn, fsAn);
        R = R_an; G = G_an; B = B_an; fs = fsAn; roiTimestamps = roiTimestampsAn; droppedFrameIdx = droppedFrameIdxAn; %#ok<NASGU>
        save(anCachePath, 'R', 'G', 'B', 'fs', 'roiTimestamps', 'droppedFrameIdx', '-v7');
        try
            fig = figure('Visible', 'off');
            imshow(debugFrameAn.image); hold on;
            rectangle('Position', debugFrameAn.foreheadBox, 'EdgeColor', 'r', 'LineWidth', 2);
            rectangle('Position', debugFrameAn.leftCheekBox, 'EdgeColor', 'c', 'LineWidth', 2);
            rectangle('Position', debugFrameAn.rightCheekBox, 'EdgeColor', 'c', 'LineWidth', 2);
            title(['Segment 36 anatomy ROI: ' subjectID], 'Interpreter', 'none');
            exportgraphics(fig, fullfile(figuresRoot, ['segment36_anatomy_roi_' subjectID '.png']));
            close(fig);
        catch
        end
    end
    anCacheReload = load(anCachePath); % need droppedFrameIdx/roiTimestamps even on a cache hit
    droppedFrameIdxAn = anCacheReload.droppedFrameIdx;
    roiTimestampsAn = anCacheReload.roiTimestamps;

    % --- Branch 1 HR, baseline vs anatomy ---
    [HR_chrom_base, HR_pos_base, HR_green_base] = branch1HR(R_base, G_base, B_base, fsBase);
    [HR_chrom_an, HR_pos_an, HR_green_an] = branch1HR(R_an, G_an, B_an, fsAn);

    fprintf('  GT spectral=%.1f beatMean=%.1f | CHROM base=%.1f an=%.1f | POS base=%.1f an=%.1f\n', ...
        gt.hrSpectralBpm, gt.hrBeatMeanBpm, HR_chrom_base, HR_chrom_an, HR_pos_base, HR_pos_an);

    hrRow = {subjectID, condition, num2str(gt.hrSpectralBpm,'%.4f'), num2str(gt.hrBeatMeanBpm,'%.4f'), ...
        num2str(HR_chrom_base,'%.4f'), num2str(HR_chrom_an,'%.4f'), ...
        num2str(HR_pos_base,'%.4f'), num2str(HR_pos_an,'%.4f'), ...
        num2str(HR_green_base,'%.4f'), num2str(HR_green_an,'%.4f'), ...
        num2str(numel(droppedFrameIdxAn)), num2str(numel(R_an)), num2str(gt.ibiCv,'%.4f')};
    writelines(strjoin(hrRow, ','), hrCsvPath, 'WriteMode', 'append');

    % --- Branch 2: shared f0 from BASELINE whole-ROI wide-band CHROM (Segment 35's own convention) ---
    [R_base_d, ~] = detrendSignal(R_base);
    [G_base_d, ~] = detrendSignal(G_base);
    [B_base_d, ~] = detrendSignal(B_base);
    [R_base_w, ~, ~] = bandpassMorphology(R_base_d, fsBase, 'wide');
    [G_base_w, ~, ~] = bandpassMorphology(G_base_d, fsBase, 'wide');
    [B_base_w, ~, ~] = bandpassMorphology(B_base_d, fsBase, 'wide');
    pulseBaseWide = chromCombine(R_base_w, G_base_w, B_base_w, R_base, G_base, B_base);
    sharedF0Hz = fftHeartRate(pulseBaseWide, fsBase) / 60;

    [confBase, corrBase, lagBase] = branchTwoConfAndCorr(R_base, G_base, B_base, fsBase, sharedF0Hz, gt, targetFsCorr, maxLagSec);
    [confAn, corrAn, lagAn] = branchTwoConfAndCorr(R_an, G_an, B_an, fsAn, sharedF0Hz, gt, targetFsCorr, maxLagSec);

    fprintf('  notchConf base=%.4f an=%.4f | waveformCorr base=%.4f (lag %.2fs) an=%.4f (lag %.2fs)\n', ...
        confBase, confAn, corrBase, lagBase, corrAn, lagAn);

    corrRow = {subjectID, condition, num2str(confBase,'%.4f'), num2str(confAn,'%.4f'), ...
        num2str(corrBase,'%.4f'), num2str(corrAn,'%.4f'), num2str(lagBase,'%.4f'), num2str(lagAn,'%.4f')};
    writelines(strjoin(corrRow, ','), corrCsvPath, 'WriteMode', 'append');
end

fprintf('\nSaved %s\nSaved %s\n', hrCsvPath, corrCsvPath);

function [hrChrom, hrPos, hrGreen] = branch1HR(R, G, B, fs)
[Rd, ~] = detrendSignal(R);
[Gd, ~] = detrendSignal(G);
[Bd, ~] = detrendSignal(B);
[Rf, ~] = bandpassClean(Rd, fs);
[Gf, ~] = bandpassClean(Gd, fs);
[Bf, ~] = bandpassClean(Bd, fs);
pulseChrom = chromCombine(Rf, Gf, Bf, R, G, B);
pulsePos = posCombine(Rf, Gf, Bf, fs, R, G, B);
hrChrom = fftHeartRate(pulseChrom, fs);
hrPos = fftHeartRate(pulsePos, fs);
hrGreen = fftHeartRate(Gf, fs);
end

function [confidence, corrVal, lagSec] = branchTwoConfAndCorr(R, G, B, fs, sharedF0Hz, gt, targetFs, maxLagSec)
[Rd, ~] = detrendSignal(R);
[Gd, ~] = detrendSignal(G);
[Bd, ~] = detrendSignal(B);
[R_ahf, ~, ~] = adaptiveHarmonicFilter(Rd, fs, 6, sharedF0Hz);
[G_ahf, ~, ~] = adaptiveHarmonicFilter(Gd, fs, 6, sharedF0Hz);
[B_ahf, ~, ~] = adaptiveHarmonicFilter(Bd, fs, 6, sharedF0Hz);
pulseAdaptive = chromCombine(R_ahf, G_ahf, B_ahf, R, G, B);

roiTimestamps = (0:numel(R)-1) / fs;

confidence = NaN;
try
    [pulseFixed, ~] = fixPolarityByGroundTruth(pulseAdaptive, roiTimestamps, gt.ppg, gt.timestamp);
    [sigUniform, ~, uniformFs] = resampleUniform(pulseFixed, roiTimestamps);
    [prototype, ~, ~, ~] = ensembleAverageBeats(sigUniform, uniformFs);
    hrBpmUsed = fftHeartRate(sigUniform, uniformFs);
    effectiveFs = numel(prototype.trimmedMean) * (hrBpmUsed / 60);
    [~, ~, ~, confidence, ~] = notchDetectIEM(prototype.trimmedMean, effectiveFs);
catch
end

corrVal = NaN;
lagSec = NaN;
try
    [sigAligned, gtAligned, ~, lagSec, ~, ~, ~, ~] = ...
        estimateLagPolarityByGroundTruth(pulseAdaptive, roiTimestamps, gt.ppg, gt.timestamp, targetFs, maxLagSec);
    sigZ = zscoreLocal(sigAligned);
    gtZ = zscoreLocal(gtAligned);
    corrVal = pearsonCorrLocal(sigZ, gtZ);
catch
end
end

function z = zscoreLocal(x)
sigma = std(x);
if sigma > 0
    z = (x - mean(x)) / sigma;
else
    z = x - mean(x);
end
end

function r = pearsonCorrLocal(x, y)
x = x(:); y = y(:);
xc = x - mean(x);
yc = y - mean(y);
denom = sqrt(sum(xc .^ 2) * sum(yc .^ 2));
if denom > 0
    r = sum(xc .* yc) / denom;
else
    r = NaN;
end
end
