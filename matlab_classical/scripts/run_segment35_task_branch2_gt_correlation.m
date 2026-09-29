% RUN_SEGMENT35_TASK_BRANCH2_GT_CORRELATION Segment 35 promotion follow-up
% (2026-09-29): the anatomy ROI was promoted to production default on Branch 1
% accuracy evidence alone (matlab/docs/Segment35_MediaPipe_Anatomy_ROI.md's
% own HR tables). This script answers the natural next question -- what does
% the anatomy ROI do to Branch 2's own waveform fidelity against the real
% ground-truth contact-PPG, not just notch-confidence pass/fail (already
% measured in results/metrics/segment35_mediapipe_anatomy_notch.csv)?
%
% METHOD (byte-for-byte the same ABPF chain as
% scripts/run_segment35_mediapipe_anatomy_roi_batch.m's own notchConfidenceFor,
% reused verbatim, not re-derived): detrendSignal -> adaptiveHarmonicFilter
% (numHarmonics=6, forced onto a SHARED f0 from the BASELINE whole-ROI
% wide-band CHROM pulse, same shared-f0 convention as Segment 7 Task H/J and
% Segment 35 Phase 1 itself) -> chromCombine -> fixPolarityByGroundTruth. NO
% confidence gate (Gaussian fallback) is applied here, matching the notch CSV's
% own ABPF-only convention, so this script's numbers are directly comparable
% to that CSV's confidence column.
%
% CORRELATION METHOD (Segment 13's own convention, morphology/
% estimateLagPolarityByGroundTruth.m + z-score + Pearson r, reused verbatim
% from scripts/run_segment13_task2_gated_selection_evaluation.m's own
% analyzeHarmonicBranch): the ABPF-combined pulse (pre-polarity-fix, i.e. the
% same pulseAdaptive this script's own fixPolarityByGroundTruth call also
% consumes) is aligned to GT PPG via a joint lag+polarity cross-correlation
% search (+/-2s), both z-scored, then compared via Pearson r. This is NOT the
% same number as fixPolarityByGroundTruth's own polarity-only correction used
% for the production notch pipeline -- it is a read-only fidelity metric.
%
% Uses ONLY already-cached data/processed/<id>_rgb_traces.mat (baseline) and
% data/processed/<id>_meshanatomy_rgb_traces.mat (anatomy) -- no video decode,
% no MediaPipe/pyenv dependency, runs in seconds.
%
% Does NOT modify pipeline/estimateVitalsAndMorphology.m,
% morphology/adaptiveHarmonicFilter.m, morphology/estimateLagPolarityByGroundTruth.m,
% morphology/notchDetectIEM.m, or any cached .mat file -- new, additive,
% read-only analysis script.
%
% Output: results/metrics/segment35_branch2_gt_correlation.csv

thisFileDir = fileparts(mfilename('fullpath'));
projectRoot = fileparts(fileparts(thisFileDir));
addpath(genpath(fullfile(projectRoot, 'matlab', 'src')));

dataset1Root = fullfile(projectRoot, 'data', 'raw', 'UBFC-rPPG', 'DATASET_1');
processedRoot = fullfile(projectRoot, 'data', 'processed');
metricsRoot = fullfile(projectRoot, 'results', 'metrics');

outCsvPath = fullfile(metricsRoot, 'segment35_branch2_gt_correlation.csv');
header = "subjectID,notchConf_baseline,notchConf_anatomy,corr_baseline,corr_anatomy,lagSec_baseline,lagSec_anatomy,maxAbsXcorr_baseline,maxAbsXcorr_anatomy";
writelines(header, outCsvPath);

ubfcSubjects = {'5-gt', '6-gt', '7-gt', '12-gt', 'after-exercise'};
targetFs = 250;
maxLagSec = 2.0;

for k = 1:numel(ubfcSubjects)
    subjectID = ubfcSubjects{k};
    fprintf('--- %s ---\n', subjectID);

    subjectDir = fullfile(dataset1Root, subjectID);
    gt = loadGroundTruth(fullfile(subjectDir, 'gtdump.xmp'), 'dataset1');

    baseCache = load(fullfile(processedRoot, [subjectID '_rgb_traces.mat']));
    R_base = baseCache.R; G_base = baseCache.G; B_base = baseCache.B; fsBase = baseCache.fs;

    anCache = load(fullfile(processedRoot, [subjectID '_meshanatomy_rgb_traces.mat']));
    R_an = anCache.R; G_an = anCache.G; B_an = anCache.B; fsAn = anCache.fs;

    % Shared f0 from the BASELINE whole-ROI wide-band CHROM pulse -- same
    % convention run_segment35_mediapipe_anatomy_roi_batch.m already used for
    % this exact subject pair's notch-confidence comparison.
    [R_base_d, ~] = detrendSignal(R_base);
    [G_base_d, ~] = detrendSignal(G_base);
    [B_base_d, ~] = detrendSignal(B_base);
    [R_base_w, ~, ~] = bandpassMorphology(R_base_d, fsBase, 'wide');
    [G_base_w, ~, ~] = bandpassMorphology(G_base_d, fsBase, 'wide');
    [B_base_w, ~, ~] = bandpassMorphology(B_base_d, fsBase, 'wide');
    pulseBaseWide = chromCombine(R_base_w, G_base_w, B_base_w, R_base, G_base, B_base);
    sharedF0Hz = fftHeartRate(pulseBaseWide, fsBase) / 60;

    [confBase, corrBase, lagBase, xcorrBase] = branchTwoConfAndCorr(R_base, G_base, B_base, fsBase, sharedF0Hz, gt, targetFs, maxLagSec);
    [confAn, corrAn, lagAn, xcorrAn] = branchTwoConfAndCorr(R_an, G_an, B_an, fsAn, sharedF0Hz, gt, targetFs, maxLagSec);

    fprintf('  notchConf   base=%.4f  anatomy=%.4f\n', confBase, confAn);
    fprintf('  waveformCorr base=%.4f  anatomy=%.4f\n', corrBase, corrAn);

    row = {subjectID, num2str(confBase,'%.4f'), num2str(confAn,'%.4f'), ...
        num2str(corrBase,'%.4f'), num2str(corrAn,'%.4f'), ...
        num2str(lagBase,'%.4f'), num2str(lagAn,'%.4f'), ...
        num2str(xcorrBase,'%.4f'), num2str(xcorrAn,'%.4f')};
    writelines(strjoin(row, ','), outCsvPath, 'WriteMode', 'append');
end

fprintf('\nSaved %s\n', outCsvPath);

function [confidence, corrVal, lagSec, maxAbsXcorr] = branchTwoConfAndCorr(R, G, B, fs, sharedF0Hz, gt, targetFs, maxLagSec)
[Rd, ~] = detrendSignal(R);
[Gd, ~] = detrendSignal(G);
[Bd, ~] = detrendSignal(B);
[R_ahf, ~, ~] = adaptiveHarmonicFilter(Rd, fs, 6, sharedF0Hz);
[G_ahf, ~, ~] = adaptiveHarmonicFilter(Gd, fs, 6, sharedF0Hz);
[B_ahf, ~, ~] = adaptiveHarmonicFilter(Bd, fs, 6, sharedF0Hz);
pulseAdaptive = chromCombine(R_ahf, G_ahf, B_ahf, R, G, B);

roiTimestamps = (0:numel(R)-1) / fs;

% --- Notch confidence, unchanged ABPF-only convention ---
[pulseFixed, ~] = fixPolarityByGroundTruth(pulseAdaptive, roiTimestamps, gt.ppg, gt.timestamp);
[sigUniform, ~, uniformFs] = resampleUniform(pulseFixed, roiTimestamps);
[prototype, ~, ~, ~] = ensembleAverageBeats(sigUniform, uniformFs);
hrBpmUsed = fftHeartRate(sigUniform, uniformFs);
effectiveFs = numel(prototype.trimmedMean) * (hrBpmUsed / 60);
[~, ~, ~, confidence, ~] = notchDetectIEM(prototype.trimmedMean, effectiveFs);

% --- Waveform correlation with GT PPG, Segment 13's own method ---
try
    [sigAligned, gtAligned, ~, lagSec, ~, ~, maxAbsXcorr, ~] = ...
        estimateLagPolarityByGroundTruth(pulseAdaptive, roiTimestamps, gt.ppg, gt.timestamp, targetFs, maxLagSec);
    sigZ = zscoreLocal(sigAligned);
    gtZ = zscoreLocal(gtAligned);
    corrVal = pearsonCorrLocal(sigZ, gtZ);
catch
    corrVal = NaN;
    lagSec = NaN;
    maxAbsXcorr = NaN;
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
