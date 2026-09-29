% RUN_SEGMENT35_MEDIAPIPE_ANATOMY_ROI_BATCH Segment 35 Phase 1 -- offline
% MATLAB evaluation of roi/faceMeshAnatomyROIExtraction.m (MediaPipe
% FaceMesh forehead+malar ROI, YCbCr skin filter -- see that function's own
% header for the Kim et al. 2021 Sensors 21:7923 motivation and the
% landmark-choice smoke test) against the existing baseline
% roi/extractROISignals.m box, EXACTLY like Segment 7 Task H/J's own
% protocol (same shared-f0 source, same ABPF chain position for notch
% confidence, same Branch-1 CHROM/POS/GREEN chain unmodified), but scaled
% to BOTH pools this session's open question (motion robustness) needs:
%   (A) 5 UBFC DATASET_1 ground-truth subjects -- notch confidence (Branch
%       2, ABPF) AND Branch-1 HR, for direct comparability with Task H/J's
%       own 4/5-baseline / 0/5-facemesh / 1/5-hybrid table.
%   (B) MAIN_112's 107 VIPL v1/source1 subjects -- Branch-1 HR only (no
%       ground-truth PPG waveform exists for VIPL, so no notch confidence
%       is computable there; matches every prior Segment 18/22/23 VIPL
%       comparison).
%   (C) The 20-subject VIPL v2 (motion) pool -- Branch-1 HR only, against
%       the SAME ground truth (segment6_task_n_region_hr_summary.csv,
%       scenario v2_motion) Segment 18's motion ablation used.
%
% Does NOT modify roi/extractROISignals.m, roi/faceMeshAnatomyROIExtraction.m,
% filtering/detrendSignal.m, filtering/bandpassClean.m,
% morphology/bandpassMorphology.m, morphology/adaptiveHarmonicFilter.m,
% pulseextraction/chromCombine.m, pulseextraction/posCombine.m,
% heartrate/fftHeartRate.m, morphology/fixPolarityByGroundTruth.m,
% morphology/resampleUniform.m, morphology/ensembleAverageBeats.m, or
% morphology/notchDetectIEM.m -- new, additive script only.
%
% RESUMABLE: each of the three output CSVs is appended to, skipping
% subject IDs already present, exactly like every prior batch script in
% this project (segment18/22 motion batches, run_vipl_integration_batch).
%
% Outputs:
%   results/metrics/segment35_mediapipe_anatomy_notch.csv        (pool A)
%   results/metrics/segment35_mediapipe_anatomy_branch1_main112.csv (pools A+B)
%   results/metrics/segment35_mediapipe_anatomy_branch1_motion20.csv (pool C)
%
% RUNTIME: faceMeshAnatomyROIExtraction.m measured ~0.11 s/frame on this
% machine (smoke test, VIPL p1/v2/source1, 1162 frames, 131s -- see
% docs/SegmentXX_MediaPipe_Anatomy_ROI.md Action 0). Expect roughly
% 4-6 hours total across all three pools; run in the background.

pyenv('ExecutionMode', 'OutOfProcess');

thisFileDir = fileparts(mfilename('fullpath'));
projectRoot = fileparts(fileparts(thisFileDir));
addpath(genpath(fullfile(projectRoot, 'matlab', 'src')));

dataset1Root = fullfile(projectRoot, 'data', 'raw', 'UBFC-rPPG', 'DATASET_1');
viplRoot = fullfile(projectRoot, 'data', 'raw', 'VIPL-HR');
metricsRoot = fullfile(projectRoot, 'results', 'metrics');
processedRoot = fullfile(projectRoot, 'data', 'processed');
figuresRoot = fullfile(projectRoot, 'results', 'figures');
if ~isfolder(figuresRoot), mkdir(figuresRoot); end

notchCsvPath = fullfile(metricsRoot, 'segment35_mediapipe_anatomy_notch.csv');
main112CsvPath = fullfile(metricsRoot, 'segment35_mediapipe_anatomy_branch1_main112.csv');
motion20CsvPath = fullfile(metricsRoot, 'segment35_mediapipe_anatomy_branch1_motion20.csv');

notchHeader = "subjectID,notchDetected_baseline,confidence_baseline,notchDetected_anatomy,confidence_anatomy,hrBpmUsed,droppedFrames_anatomy,numFrames_anatomy";
main112Header = "subjectID,dataset,HR_groundtruth,HR_chrom_baseline,HR_chrom_anatomy,HR_pos_baseline,HR_pos_anatomy,HR_green_baseline,HR_green_anatomy,droppedFrames_anatomy,numFrames_anatomy";
motion20Header = "subjectID,HR_groundtruth,HR_chrom_baseline,HR_chrom_anatomy,HR_pos_baseline,HR_pos_anatomy,HR_green_baseline,HR_green_anatomy,droppedFrames_anatomy,numFrames_anatomy";

if ~isfile(notchCsvPath), writelines(notchHeader, notchCsvPath); end
if ~isfile(main112CsvPath), writelines(main112Header, main112CsvPath); end
if ~isfile(motion20CsvPath), writelines(motion20Header, motion20CsvPath); end

alreadyDoneNotch = readDoneIds(notchCsvPath);
alreadyDoneMain112 = readDoneIds(main112CsvPath);
alreadyDoneMotion20 = readDoneIds(motion20CsvPath);

failedSubjects = {};

%% ---- Pool A: 5 UBFC ground-truth subjects (notch + Branch 1) ----
ubfcSubjects = {'5-gt', '6-gt', '7-gt', '12-gt', 'after-exercise'};

for k = 1:numel(ubfcSubjects)
    subjectID = ubfcSubjects{k};
    needNotch = ~any(strcmp(alreadyDoneNotch, subjectID));
    needMain = ~any(strcmp(alreadyDoneMain112, subjectID));
    if ~needNotch && ~needMain
        continue
    end
    fprintf('--- Segment 35 UBFC subject %s (notch=%d, branch1=%d) ---\n', subjectID, needNotch, needMain);
    tSubj = tic;
    try
        subjectDir = fullfile(dataset1Root, subjectID);
        aviFiles = dir(fullfile(subjectDir, '*.avi'));
        videoPath = fullfile(subjectDir, aviFiles(1).name);
        gt = loadGroundTruth(fullfile(subjectDir, 'gtdump.xmp'), 'dataset1');

        cachedBasePath = fullfile(processedRoot, [subjectID '_rgb_traces.mat']);
        if isfile(cachedBasePath)
            c = load(cachedBasePath);
            R_base = c.R; G_base = c.G; B_base = c.B; fsBase = c.fs;
        else
            [framesBase, fsBase, ~] = loadUBFCVideo(videoPath);
            [R_base, G_base, B_base, ~, ~, ~] = extractROISignals(framesBase, fsBase);
            R = R_base; G = G_base; B = B_base; fs = fsBase; %#ok<NASGU>
            save(cachedBasePath, 'R', 'G', 'B', 'fs', '-v7');
        end

        anatomyCachePath = fullfile(processedRoot, [subjectID '_meshanatomy_rgb_traces.mat']);
        if isfile(anatomyCachePath)
            ca = load(anatomyCachePath);
            R_an = ca.R; G_an = ca.G; B_an = ca.B; fsAn = ca.fs;
            roiTimestampsAn = ca.roiTimestamps; droppedFrameIdxAn = ca.droppedFrameIdx;
        else
            [framesAn, fsAn, ~] = loadUBFCVideo(videoPath);
            [R_an, G_an, B_an, roiTimestampsAn, droppedFrameIdxAn, debugFrameAn] = faceMeshAnatomyROIExtraction(framesAn, fsAn);
            R = R_an; G = G_an; B = B_an; fs = fsAn; roiTimestamps = roiTimestampsAn; droppedFrameIdx = droppedFrameIdxAn; %#ok<NASGU>
            save(anatomyCachePath, 'R', 'G', 'B', 'fs', 'roiTimestamps', 'droppedFrameIdx', '-v7');
            saveDebugFigure(figuresRoot, subjectID, debugFrameAn);
        end

        % --- Branch 1 (unmodified chain), baseline vs anatomy ---
        [HR_chrom_base, HR_pos_base, HR_green_base] = branch1HR(R_base, G_base, B_base, fsBase);
        [HR_chrom_an, HR_pos_an, HR_green_an] = branch1HR(R_an, G_an, B_an, fsAn);

        videoDurationSec = numel(R_base) / fsBase;
        inClipMask = gt.timestamp <= videoDurationSec;
        HR_gt = mean(gt.hr(inClipMask));

        if needMain
            row = {subjectID, 'UBFC', num2str(HR_gt,'%.4f'), num2str(HR_chrom_base,'%.4f'), num2str(HR_chrom_an,'%.4f'), ...
                num2str(HR_pos_base,'%.4f'), num2str(HR_pos_an,'%.4f'), num2str(HR_green_base,'%.4f'), num2str(HR_green_an,'%.4f'), ...
                num2str(numel(droppedFrameIdxAn)), num2str(numel(R_an))};
            writelines(strjoin(row, ','), main112CsvPath, 'WriteMode', 'append');
        end

        if needNotch
            % --- Shared f0 from baseline whole-ROI wide-band CHROM (Task H/J convention) ---
            [R_base_d, ~] = detrendSignal(R_base);
            [G_base_d, ~] = detrendSignal(G_base);
            [B_base_d, ~] = detrendSignal(B_base);
            [R_base_w, ~, ~] = bandpassMorphology(R_base_d, fsBase, 'wide');
            [G_base_w, ~, ~] = bandpassMorphology(G_base_d, fsBase, 'wide');
            [B_base_w, ~, ~] = bandpassMorphology(B_base_d, fsBase, 'wide');
            pulseBaseWide = chromCombine(R_base_w, G_base_w, B_base_w, R_base, G_base, B_base);
            sharedF0Hz = fftHeartRate(pulseBaseWide, fsBase) / 60;

            [notchDetBase, confBase] = notchConfidenceFor(R_base, G_base, B_base, fsBase, sharedF0Hz, gt);
            [notchDetAn, confAn, hrBpmUsedAn] = notchConfidenceFor(R_an, G_an, B_an, fsAn, sharedF0Hz, gt);

            row = {subjectID, num2str(notchDetBase), num2str(confBase,'%.4f'), num2str(notchDetAn), num2str(confAn,'%.4f'), ...
                num2str(hrBpmUsedAn,'%.4f'), num2str(numel(droppedFrameIdxAn)), num2str(numel(R_an))};
            writelines(strjoin(row, ','), notchCsvPath, 'WriteMode', 'append');
        end

        fprintf('%s done in %.0fs: HR_chrom base=%.2f anatomy=%.2f | HR_pos base=%.2f anatomy=%.2f (GT=%.2f)\n', ...
            subjectID, toc(tSubj), HR_chrom_base, HR_chrom_an, HR_pos_base, HR_pos_an, HR_gt);
    catch causeErr
        fprintf('%s: FAILED -- %s -- %s\n', subjectID, causeErr.identifier, causeErr.message);
        failedSubjects{end+1} = subjectID; %#ok<AGROW>
    end
end

%% ---- Pool B: MAIN_112's 107 VIPL v1/source1 subjects (Branch 1 only) ----
mainVipl = readtable(fullfile(metricsRoot, 'segment4_hr_summary_vipl.csv'), 'TextType', 'string');

for k = 1:height(mainVipl)
    subjectID = char(mainVipl.subjectID(k));
    if any(strcmp(alreadyDoneMain112, subjectID)), continue, end
    tok = regexp(subjectID, '^VIPL_p(\d+)_v(\d+)_source(\d+)$', 'tokens', 'once');
    pNum = str2double(tok{1}); vNum = str2double(tok{2}); sNum = str2double(tok{3});
    fprintf('--- Segment 35 MAIN_112 VIPL subject %s (%d/%d) ---\n', subjectID, k, height(mainVipl));
    tSubj = tic;
    try
        cachedBasePath = fullfile(processedRoot, [subjectID '_rgb_traces.mat']);
        c = load(cachedBasePath); % must exist -- verified before this script was written
        R_base = c.R; G_base = c.G; B_base = c.B; fsBase = c.fs;

        anatomyCachePath = fullfile(processedRoot, [subjectID '_meshanatomy_rgb_traces.mat']);
        if isfile(anatomyCachePath)
            ca = load(anatomyCachePath);
            R_an = ca.R; G_an = ca.G; B_an = ca.B; fsAn = ca.fs; droppedFrameIdxAn = ca.droppedFrameIdx;
        else
            [framesAn, fsAn, ~] = loadVIPLVideo(viplRoot, pNum, vNum, sNum);
            [R_an, G_an, B_an, roiTimestampsAn, droppedFrameIdxAn, debugFrameAn] = faceMeshAnatomyROIExtraction(framesAn, fsAn);
            R = R_an; G = G_an; B = B_an; fs = fsAn; roiTimestamps = roiTimestampsAn; droppedFrameIdx = droppedFrameIdxAn; %#ok<NASGU>
            save(anatomyCachePath, 'R', 'G', 'B', 'fs', 'roiTimestamps', 'droppedFrameIdx', '-v7');
            if mod(k, 10) == 1
                saveDebugFigure(figuresRoot, subjectID, debugFrameAn);
            end
        end

        [HR_chrom_base, HR_pos_base, HR_green_base] = branch1HR(R_base, G_base, B_base, fsBase);
        [HR_chrom_an, HR_pos_an, HR_green_an] = branch1HR(R_an, G_an, B_an, fsAn);

        HR_gt = mainVipl.HR_groundtruth(k);

        row = {subjectID, 'VIPL', num2str(HR_gt,'%.4f'), num2str(HR_chrom_base,'%.4f'), num2str(HR_chrom_an,'%.4f'), ...
            num2str(HR_pos_base,'%.4f'), num2str(HR_pos_an,'%.4f'), num2str(HR_green_base,'%.4f'), num2str(HR_green_an,'%.4f'), ...
            num2str(numel(droppedFrameIdxAn)), num2str(numel(R_an))};
        writelines(strjoin(row, ','), main112CsvPath, 'WriteMode', 'append');

        fprintf('%s done in %.0fs: HR_chrom base=%.2f anatomy=%.2f (GT=%.2f, dropped=%d/%d)\n', ...
            subjectID, toc(tSubj), HR_chrom_base, HR_chrom_an, HR_gt, numel(droppedFrameIdxAn), numel(R_an));
    catch causeErr
        fprintf('%s: FAILED -- %s -- %s\n', subjectID, causeErr.identifier, causeErr.message);
        failedSubjects{end+1} = subjectID; %#ok<AGROW>
    end
end

%% ---- Pool C: 20-subject VIPL v2 (motion) pool (Branch 1 only) ----
taskN = readtable(fullfile(metricsRoot, 'segment6_task_n_region_hr_summary.csv'), 'TextType', 'string');
taskN = taskN(taskN.scenario == "v2_motion", :);

for k = 1:height(taskN)
    subjectID = char(taskN.subjectID(k));
    if any(strcmp(alreadyDoneMotion20, subjectID)), continue, end
    tok = regexp(subjectID, '^VIPL_p(\d+)_v(\d+)_source(\d+)$', 'tokens', 'once');
    pNum = str2double(tok{1}); vNum = str2double(tok{2}); sNum = str2double(tok{3});
    fprintf('--- Segment 35 motion-pool VIPL subject %s (%d/%d) ---\n', subjectID, k, height(taskN));
    tSubj = tic;
    try
        [framesBase, fsBase, ~] = loadVIPLVideo(viplRoot, pNum, vNum, sNum);
        [R_base, G_base, B_base, ~, ~, ~] = extractROISignals(framesBase, fsBase);

        anatomyCachePath = fullfile(processedRoot, [subjectID '_meshanatomy_rgb_traces.mat']);
        if isfile(anatomyCachePath)
            ca = load(anatomyCachePath);
            R_an = ca.R; G_an = ca.G; B_an = ca.B; fsAn = ca.fs; droppedFrameIdxAn = ca.droppedFrameIdx;
        else
            [framesAn, fsAn, ~] = loadVIPLVideo(viplRoot, pNum, vNum, sNum);
            [R_an, G_an, B_an, roiTimestampsAn, droppedFrameIdxAn, debugFrameAn] = faceMeshAnatomyROIExtraction(framesAn, fsAn);
            R = R_an; G = G_an; B = B_an; fs = fsAn; roiTimestamps = roiTimestampsAn; droppedFrameIdx = droppedFrameIdxAn; %#ok<NASGU>
            save(anatomyCachePath, 'R', 'G', 'B', 'fs', 'roiTimestamps', 'droppedFrameIdx', '-v7');
            saveDebugFigure(figuresRoot, subjectID, debugFrameAn);
        end

        [HR_chrom_base, HR_pos_base, HR_green_base] = branch1HR(R_base, G_base, B_base, fsBase);
        [HR_chrom_an, HR_pos_an, HR_green_an] = branch1HR(R_an, G_an, B_an, fsAn);

        HR_gt = taskN.HR_groundtruth(k);

        row = {subjectID, num2str(HR_gt,'%.4f'), num2str(HR_chrom_base,'%.4f'), num2str(HR_chrom_an,'%.4f'), ...
            num2str(HR_pos_base,'%.4f'), num2str(HR_pos_an,'%.4f'), num2str(HR_green_base,'%.4f'), num2str(HR_green_an,'%.4f'), ...
            num2str(numel(droppedFrameIdxAn)), num2str(numel(R_an))};
        writelines(strjoin(row, ','), motion20CsvPath, 'WriteMode', 'append');

        fprintf('%s done in %.0fs: HR_chrom base=%.2f anatomy=%.2f (GT=%.2f, dropped=%d/%d)\n', ...
            subjectID, toc(tSubj), HR_chrom_base, HR_chrom_an, HR_gt, numel(droppedFrameIdxAn), numel(R_an));
    catch causeErr
        fprintf('%s: FAILED -- %s -- %s\n', subjectID, causeErr.identifier, causeErr.message);
        failedSubjects{end+1} = subjectID; %#ok<AGROW>
    end
end

fprintf('--- Segment 35 Phase 1 batch complete. %d failures. ---\n', numel(failedSubjects));
for i = 1:numel(failedSubjects)
    fprintf('  FAILED: %s\n', failedSubjects{i});
end

%% ---- Helper functions ----
function ids = readDoneIds(csvPath)
ids = {};
if isfile(csvPath)
    t = readtable(csvPath, 'TextType', 'string');
    if height(t) > 0
        ids = cellstr(t.subjectID);
    end
end
end

function [HR_chrom, HR_pos, HR_green] = branch1HR(R, G, B, fs)
[Rd, ~] = detrendSignal(R);
[Gd, ~] = detrendSignal(G);
[Bd, ~] = detrendSignal(B);
[Rf, ~] = bandpassClean(Rd, fs);
[Gf, ~] = bandpassClean(Gd, fs);
[Bf, ~] = bandpassClean(Bd, fs);

pulseChrom = chromCombine(Rf, Gf, Bf, R, G, B);
pulseChromF = bandpassClean(pulseChrom, fs);
HR_chrom = fftHeartRate(pulseChromF, fs);

pulsePos = posCombine(Rf, Gf, Bf, fs, R, G, B);
pulsePosF = bandpassClean(pulsePos, fs);
HR_pos = fftHeartRate(pulsePosF, fs);

HR_green = fftHeartRate(Gf, fs);
end

function [notchDetected, confidence, hrBpmUsed] = notchConfidenceFor(R, G, B, fs, sharedF0Hz, gt)
[Rd, ~] = detrendSignal(R);
[Gd, ~] = detrendSignal(G);
[Bd, ~] = detrendSignal(B);
[R_ahf, ~, ~] = adaptiveHarmonicFilter(Rd, fs, 6, sharedF0Hz);
[G_ahf, ~, ~] = adaptiveHarmonicFilter(Gd, fs, 6, sharedF0Hz);
[B_ahf, ~, ~] = adaptiveHarmonicFilter(Bd, fs, 6, sharedF0Hz);
pulseAdaptive = chromCombine(R_ahf, G_ahf, B_ahf, R, G, B);

roiTimestamps = (0:numel(R)-1) / fs;
[pulseFixed, ~] = fixPolarityByGroundTruth(pulseAdaptive, roiTimestamps, gt.ppg, gt.timestamp);
[sigUniform, ~, uniformFs] = resampleUniform(pulseFixed, roiTimestamps);
[prototype, ~, ~, ~] = ensembleAverageBeats(sigUniform, uniformFs);

hrBpmUsed = fftHeartRate(sigUniform, uniformFs);
effectiveFs = numel(prototype.trimmedMean) * (hrBpmUsed / 60);
[notchDetected, ~, ~, confidence, ~] = notchDetectIEM(prototype.trimmedMean, effectiveFs);
end

function saveDebugFigure(figuresRoot, subjectID, debugFrame)
try
    fig = figure('Visible', 'off');
    imshow(debugFrame.image); hold on;
    rectangle('Position', debugFrame.foreheadBox, 'EdgeColor', 'r', 'LineWidth', 2);
    rectangle('Position', debugFrame.leftCheekBox, 'EdgeColor', 'c', 'LineWidth', 2);
    rectangle('Position', debugFrame.rightCheekBox, 'EdgeColor', 'c', 'LineWidth', 2);
    title(['Segment 35 anatomy ROI: ' subjectID], 'Interpreter', 'none');
    pngPath = fullfile(figuresRoot, ['segment35_anatomy_roi_' subjectID '.png']);
    exportgraphics(fig, pngPath);
    close(fig);
catch
    % debug figure is best-effort only; never fail the batch over it
end
end
