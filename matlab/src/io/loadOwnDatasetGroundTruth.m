function gt = loadOwnDatasetGroundTruth(fullCsvPath, summaryCsvPath, subjectKey)
% LOADOWNDATASETGROUNDTRUTH Loads one subject's ground-truth PPG from the
% "Own Dataset" (AFE4404 finger-PPG device, 9 subjects, recorded
% 2026-09-19 -- see Own Dataset/Ground_Truth_PPG_All_Subjects/.../README.txt
% for the full device/processing description). This is the first loader
% for this dataset in this project -- it was identified in
% docs/Segment35_Accuracy_Research_and_Plan.md (Test 2) but never
% integrated (that test was gated on the cancelled DL-model evaluation).
%
% Inputs:
%   fullCsvPath    - path to <subject>_ground_truth_ppg_full.csv (columns:
%                    sample_number, time_s, raw_LED1_minus_amb_V,
%                    gt_ppg_au -- 100 Hz, DC-removed + 0.5-8 Hz bandpassed
%                    contact PPG, per the dataset's own README).
%   summaryCsvPath - path to SUMMARY_ground_truth_HR_SpO2.csv (one row per
%                    subject, three independent HR estimates).
%   subjectKey     - string/char, the "subject" column value in
%                    summaryCsvPath for this recording (NOT necessarily
%                    the video's own folder/file name -- see the caller's
%                    own subject-mapping table for the real
%                    video<->ground-truth correspondence, cross-verified
%                    via matching embedded capture timestamps in each
%                    subject's own *_Volts_<timestamp>.csv filename, since
%                    two of the nine (Abrar/Normal -> "Device_test",
%                    Lubaba/After Breath Hold -> "Lubaba_after_exercise")
%                    do NOT share a name with their video folder).
%
% Outputs:
%   gt - struct:
%     ppg           - 1 x N, gt_ppg_au (already DC-removed + bandpassed,
%                     per the dataset's own README -- fed directly to
%                     morphology/fixPolarityByGroundTruth.m /
%                     estimateLagPolarityByGroundTruth.m the same way
%                     io/loadGroundTruth.m's gt.ppg is).
%     timestamp     - 1 x N, seconds (the CSV's own time_s column).
%     hrBeatMeanBpm, hrBeatMedianBpm, hrSpectralBpm - scalars, from the
%                     summary CSV's own three independent HR estimates.
%     spo2Pct       - scalar. NOT reliable ground truth -- the dataset's
%                     own README states this plainly (unconfirmed LED
%                     wavelength assignment, near-noise-floor second
%                     channel); carried through for completeness only,
%                     never compared against this pipeline's SpO2 output.
%     ibiCv         - scalar, inter-beat-interval coefficient of variation
%                     (the dataset's own irregularity flag; >~0.10 means
%                     treat hr* as approximate, per the README).

fullData = readtable(fullCsvPath);
gt.ppg = fullData.gt_ppg_au(:)';
gt.timestamp = fullData.time_s(:)';

summaryData = readtable(summaryCsvPath, 'TextType', 'string');
rowMask = strcmpi(summaryData.subject, subjectKey);
if ~any(rowMask)
    error('loadOwnDatasetGroundTruth:subjectNotFound', 'subjectKey "%s" not found in %s.', subjectKey, summaryCsvPath);
end
row = summaryData(rowMask, :);

gt.hrBeatMeanBpm = row.hr_beat_mean_bpm(1);
gt.hrBeatMedianBpm = row.hr_beat_median_bpm(1);
gt.hrSpectralBpm = row.hr_spectral_bpm(1);
gt.spo2Pct = row.spo2_est_pct(1);
gt.ibiCv = row.ibi_cv(1);

end
