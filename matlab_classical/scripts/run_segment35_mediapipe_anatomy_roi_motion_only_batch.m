% RUN_SEGMENT35_MEDIAPIPE_ANATOMY_ROI_MOTION_ONLY_BATCH Segment 35 Phase 1
% follow-up -- runs ONLY Pool C (the 20-subject VIPL v2 motion pool) from
% run_segment35_mediapipe_anatomy_roi_batch.m, which this file is extracted
% from verbatim (same helper functions, same CSV schema/path). Exists
% because the main script processes pools in order (A: 5 UBFC, B: 107 VIPL
% v1, C: 20 VIPL v2 motion) and Segment 35's own doc
% (matlab/docs/Segment35_MediaPipe_Anatomy_ROI.md) flagged the motion pool
% as the actual open question this phase exists to answer -- running it
% directly, rather than waiting through pool B's remaining ~44 subjects
% first, gets that answer sooner.
%
% Fully compatible with the main script: writes to the SAME
% results/metrics/segment35_mediapipe_anatomy_branch1_motion20.csv (skips
% any subject already present, exactly the same resumability contract), so
% running this script now and the main script again later (to finish pool
% B) never conflicts or duplicates work.
%
% Does NOT modify roi/extractROISignals.m, roi/faceMeshAnatomyROIExtraction.m,
% filtering/detrendSignal.m, filtering/bandpassClean.m,
% pulseextraction/chromCombine.m, pulseextraction/posCombine.m,
% heartrate/fftHeartRate.m -- new, additive script only.

pyenv('ExecutionMode', 'OutOfProcess');

thisFileDir = fileparts(mfilename('fullpath'));
projectRoot = fileparts(fileparts(thisFileDir));
addpath(genpath(fullfile(projectRoot, 'matlab', 'src')));

viplRoot = fullfile(projectRoot, 'data', 'raw', 'VIPL-HR');
metricsRoot = fullfile(projectRoot, 'results', 'metrics');
processedRoot = fullfile(projectRoot, 'data', 'processed');
figuresRoot = fullfile(projectRoot, 'results', 'figures');
if ~isfolder(figuresRoot), mkdir(figuresRoot); end

motion20CsvPath = fullfile(metricsRoot, 'segment35_mediapipe_anatomy_branch1_motion20.csv');
motion20Header = "subjectID,HR_groundtruth,HR_chrom_baseline,HR_chrom_anatomy,HR_pos_baseline,HR_pos_anatomy,HR_green_baseline,HR_green_anatomy,droppedFrames_anatomy,numFrames_anatomy";
if ~isfile(motion20CsvPath), writelines(motion20Header, motion20CsvPath); end

alreadyDoneMotion20 = readDoneIds(motion20CsvPath);
failedSubjects = {};

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

fprintf('--- Segment 35 motion-pool-only batch complete. %d failures. ---\n', numel(failedSubjects));
for i = 1:numel(failedSubjects)
    fprintf('  FAILED: %s\n', failedSubjects{i});
end

%% ---- Helper functions (identical to the main batch script) ----
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
