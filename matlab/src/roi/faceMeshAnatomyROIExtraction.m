function [R, G, B, roiTimestamps, droppedFrameIdx, debugFrame] = faceMeshAnatomyROIExtraction(frames, frameRate)
% FACEMESHANATOMYROIEXTRACTION Segment 35 Phase 1 -- anatomy-informed
% multi-region ROI: forehead (glabella + medial forehead) + both malar
% (cheekbone) regions, landmark-driven via MediaPipe FaceMesh, with a
% classical YCbCr skin-tone pixel filter -- rather than
% roi/extractROISignals.m's fixed FACE-BOX FRACTION boxes (Segment 6 Task
% N's 'glabella'/'malar' modes are already face-box-relative
% approximations; this file replaces the face-box-fraction geometry with
% real per-frame landmark geometry for the SAME two anatomical targets).
%
% WHY THESE REGIONS: Kim, Lee & Sohn (2021), Sensors 21:7923, "Assessment
% of ROI Selection for Facial Video-Based rPPG" (VERIFIED-FULL via PMC,
% PMC8659899, 2026-09-28), ranked 39 face-mesh-divided anatomical regions
% by dermis/epidermis thickness (Chopra et al. 2015 cadaver data) combined
% with measured BVP-similarity accuracy on UBFC-rPPG/LGI-PPGI. Their TOP-5
% regions were: right malar, glabella, lower medial forehead, upper medial
% forehead, left malar -- i.e. exactly "forehead + both cheeks (malar)",
% the combination this file builds. They used Google's MediaPipe (468-
% point face mesh) for landmark detection, the same detector Segment 7
% Task H/J already validated for this project (see
% docs/Segment7_Task_H_FaceMesh_ROI.md Action 0 for the OutOfProcess/
% pyenv feasibility work this file reuses verbatim). The paper's own
% Chopra-atlas region boundaries are not given as MediaPipe landmark
% indices, so the two polygons below are this project's own landmark-based
% construction of the same two anatomical targets (forehead-above-
% eyebrows, cheekbone-below-eye-above-mouth) -- verified by inspection on
% a real UBFC frame (subject 5-gt, frame ~100) before being committed here
% (see matlab/docs/SegmentXX_MediaPipe_Anatomy_ROI.md Action 0 for the
% two-round landmark smoke test that ruled out the first candidate cheek
% landmarks, which sat at glasses/eye height, not cheekbone height).
%
% SKIN-PIXEL FILTER: classical YCbCr skin-tone thresholding (Chai & Ngan
% 1999-style range, Cb in [77,127], Cr in [133,173], on BT.601 YCbCr
% converted from the MATLAB uint8 RGB frame), applied per-region-mask
% before averaging. Chosen over a pretrained DL segmentation model because
% (a) it needs no model download/inference cost on top of the already-
% Python-round-trip-bound MediaPipe call, (b) it is deterministic and
% inspectable (no black-box mask), and (c) the two target regions
% (forehead, malar) are small, mostly-frontal, already-cropped patches
% where a simple chrominance gate is enough to reject hair/glasses-frame/
% shadow pixels that the landmark box alone can't exclude -- exactly the
% failure mode the Action 0 smoke test found (glasses temple pixels
% inside a naive cheek box). If a region's skin-filtered pixel count is
% below 10% of its box's pixel count (e.g. a heavy shadow or extreme
% color-cast frame), the filter is skipped for that frame/region and the
% raw box average is used instead, logged via droppedFrameIdx's sibling
% skinFilterSkippedIdx in debugFrame -- never silently average zero
% pixels.
%
% Does NOT modify roi/faceMeshROIExtraction.m, roi/faceMeshHybridROIExtraction.m,
% or roi/extractROISignals.m -- new, additive file, reusing Task H/J's
% mediapipe setup/detection-loop pattern (pyenv OutOfProcess, per-frame
% py.eval batched landmark extraction) verbatim.
%
% REQUIRED ONE-TIME SESSION SETUP (same as faceMeshROIExtraction.m /
% faceMeshHybridROIExtraction.m):
%   pyenv('ExecutionMode', 'OutOfProcess');
% before this function (or anything else touching py.*) is called in the
% current MATLAB session.
%
% Inputs:
%   frames    - a VideoReader object, as produced by io/loadUBFCVideo.m or
%               io/loadVIPLVideo.m.
%   frameRate - scalar, frames per second.
%
% Outputs: same shape/convention as roi/extractROISignals.m --
%   R, G, B         - 1 x numFrames, POOLED forehead+leftCheek+rightCheek
%                     skin-filtered pixels, concatenated then averaged
%                     (same "concatenate-then-average" convention as
%                     extractROISignals.m's bilateral malar/cheek modes,
%                     so this is a direct drop-in for the shared CHROM/
%                     POS/notch pipeline -- one trace in, unchanged from
%                     there).
%   roiTimestamps   - 1 x numFrames, seconds.
%   droppedFrameIdx - frame indices where MediaPipe found no face and the
%                     last known good landmark set was reused (or, before
%                     any detection has ever succeeded, a centered
%                     fallback).
%   debugFrame      - struct: image, foreheadBox/leftCheekBox/rightCheekBox
%                     (each [x y width height], pixel coords, for a sanity
%                     PNG), frameIndex, skinFilterSkippedIdx (frame
%                     indices where the skin filter fell back to the raw
%                     box average for at least one region).

pe = pyenv();
if pe.ExecutionMode ~= "OutOfProcess"
    error('faceMeshAnatomyROIExtraction:wrongExecutionMode', ...
        ['pyenv ExecutionMode is "%s", not "OutOfProcess". In-process mode is confirmed to crash on ' ...
         'mediapipe''s native bindings (DLL load failure against MATLAB''s own bundled libraries). Call ' ...
         'pyenv(''ExecutionMode'', ''OutOfProcess'') BEFORE any other py.* call in this MATLAB session ' ...
         '(it cannot be changed once Python is loaded), then retry.'], char(pe.ExecutionMode));
end

mpModule = py.importlib.import_module('mediapipe');
faceMeshModule = py.getattr(mpModule.solutions, 'face_mesh');
faceMesh = faceMeshModule.FaceMesh(pyargs('static_image_mode', false, 'max_num_faces', int32(1), ...
    'refine_landmarks', false, 'min_detection_confidence', 0.5, 'min_tracking_confidence', 0.5));

% Landmark indices (0-based MediaPipe convention; +1 applied at use site
% for MATLAB's 1-based indexing into the per-frame landmark array).
% Forehead: top-of-forehead point (10) to eyebrow line (105 left outer
% brow / 334 right outer brow set the x-extent and the y "bottom" of the
% forehead box, i.e. it stops AT the eyebrows, not below them).
lm.foreheadTop = 10;
lm.leftBrowOuter = 105;
lm.rightBrowOuter = 334;
% Left/right malar (cheekbone): y-span from under-eye (111/340) down to
% the nasolabial fold just above the mouth corner (216/436, NOT the mouth
% corner itself, 61/291 -- kept well above the mouth, matching this
% project's existing malar-mode intent of avoiding talking-related
% motion); x-span from an outer-cheek point near the ear (137/366) to a
% nose-side point (129/358 left / 358... see below), i.e. away from both
% the nose and the ear/jaw edge.
lm.leftUnderEye = 111;
lm.leftNasolabial = 216;
lm.leftCheekOuter = 137;
lm.leftNoseAla = 129;
lm.rightUnderEye = 340;
lm.rightNasolabial = 436;
lm.rightCheekOuter = 366;
lm.rightNoseAla = 358;

frames.CurrentTime = 0;
numFrames = frames.NumFrames;

R = zeros(1, numFrames);
G = zeros(1, numFrames);
B = zeros(1, numFrames);
roiTimestamps = zeros(1, numFrames);
frameDroppedFlag = false(1, numFrames);
skinFilterSkippedFlag = false(1, numFrames);

lastGoodLm = [];

debugFrame = struct();
debugFrame.image = [];
debugFrame.foreheadBox = [];
debugFrame.leftCheekBox = [];
debugFrame.rightCheekBox = [];
debugFrame.frameIndex = round(numFrames / 2);

frameIdx = 0;

while hasFrame(frames)
    frameIdx = frameIdx + 1;
    if frameIdx > numFrames
        break
    end

    img = readFrame(frames); % MATLAB uint8 H x W x 3, RGB order already
    [frameHeight, frameWidth, ~] = size(img);

    pyImg = py.numpy.array(img);
    detResult = faceMesh.process(pyImg);

    frameDropped = false;

    if isempty(detResult.multi_face_landmarks) || detResult.multi_face_landmarks == py.None
        frameDropped = true;
        if frameIdx <= 5 || mod(frameIdx, 200) == 0
            disp(['Frame ' num2str(frameIdx) ': mediapipe FaceMesh found no face, reusing last known landmarks.']);
        end
        currentLm = lastGoodLm;
    else
        faceLandmarks = detResult.multi_face_landmarks{1};
        landmarkList = py.getattr(faceLandmarks, 'landmark');
        % Batched single Python-side list comprehension -- Segment 7 Task
        % J's fix for the per-landmark MATLAB<->Python round-trip leak
        % (see faceMeshHybridROIExtraction.m's header for the crash this
        % avoids). Only the 9 named landmarks above are actually needed,
        % but reading all 468 in one batched call and indexing locally is
        % simpler and no slower (one round trip either way).
        localsDict = py.dict(pyargs('lm', landmarkList));
        xyList = py.eval('[[p.x, p.y] for p in lm]', py.dict(), localsDict);
        xyArray = double(py.numpy.array(xyList)); % numLandmarks x 2, normalized [0,1]
        allX = xyArray(:, 1)' * frameWidth;
        allY = xyArray(:, 2)' * frameHeight;
        currentLm = struct('x', allX, 'y', allY);
        lastGoodLm = currentLm;
    end

    if isempty(currentLm)
        % No detection has EVER succeeded yet -- centered fallback boxes,
        % same "no history yet" philosophy as extractROISignals.m's
        % centered fallback box.
        frameDropped = true;
        [foreheadBox, leftCheekBox, rightCheekBox] = centeredFallbackBoxes(frameWidth, frameHeight);
    else
        [foreheadBox, leftCheekBox, rightCheekBox] = landmarksToBoxes(currentLm, lm, frameWidth, frameHeight);
    end

    frameDroppedFlag(frameIdx) = frameDropped;

    [redPool, greenPool, bluePool, skippedFilter] = poolSkinPixels(img, {foreheadBox, leftCheekBox, rightCheekBox});
    skinFilterSkippedFlag(frameIdx) = skippedFilter;

    R(frameIdx) = mean(redPool);
    G(frameIdx) = mean(greenPool);
    B(frameIdx) = mean(bluePool);
    roiTimestamps(frameIdx) = (frameIdx - 1) / frameRate;

    if frameIdx == debugFrame.frameIndex
        debugFrame.image = img;
        debugFrame.foreheadBox = foreheadBox;
        debugFrame.leftCheekBox = leftCheekBox;
        debugFrame.rightCheekBox = rightCheekBox;
        debugFrame.frameIndex = frameIdx;
    end
end

actualNumFrames = frameIdx;

if actualNumFrames < numFrames
    R = R(1:actualNumFrames);
    G = G(1:actualNumFrames);
    B = B(1:actualNumFrames);
    roiTimestamps = roiTimestamps(1:actualNumFrames);
    frameDroppedFlag = frameDroppedFlag(1:actualNumFrames);
    skinFilterSkippedFlag = skinFilterSkippedFlag(1:actualNumFrames);
end

droppedFrameIdx = find(frameDroppedFlag);
debugFrame.skinFilterSkippedIdx = find(skinFilterSkippedFlag);

if isempty(debugFrame.image)
    debugFrame.image = img;
    debugFrame.foreheadBox = foreheadBox;
    debugFrame.leftCheekBox = leftCheekBox;
    debugFrame.rightCheekBox = rightCheekBox;
    debugFrame.frameIndex = frameIdx;
end

end

function [foreheadBox, leftCheekBox, rightCheekBox] = landmarksToBoxes(currentLm, lm, frameWidth, frameHeight)

x = currentLm.x; y = currentLm.y; % 1-based access below: MediaPipe idx + 1

fx1 = min(x(lm.leftBrowOuter + 1), x(lm.rightBrowOuter + 1));
fx2 = max(x(lm.leftBrowOuter + 1), x(lm.rightBrowOuter + 1));
fy2 = mean([y(lm.leftBrowOuter + 1), y(lm.rightBrowOuter + 1)]); % eyebrow line, bottom
fyTop = y(lm.foreheadTop + 1);
fy1 = fyTop + 0.15 * (fy2 - fyTop); % small margin below the hairline-adjacent top landmark
foreheadBox = clampBox([fx1, fy1, fx2 - fx1, fy2 - fy1], frameWidth, frameHeight);

lx1 = min(x(lm.leftCheekOuter + 1), x(lm.leftNoseAla + 1));
lx2 = max(x(lm.leftCheekOuter + 1), x(lm.leftNoseAla + 1));
ly1 = y(lm.leftUnderEye + 1);
ly2 = y(lm.leftNasolabial + 1);
leftCheekBox = clampBox([lx1, min(ly1, ly2), lx2 - lx1, abs(ly2 - ly1)], frameWidth, frameHeight);

rx1 = min(x(lm.rightNoseAla + 1), x(lm.rightCheekOuter + 1));
rx2 = max(x(lm.rightNoseAla + 1), x(lm.rightCheekOuter + 1));
ry1 = y(lm.rightUnderEye + 1);
ry2 = y(lm.rightNasolabial + 1);
rightCheekBox = clampBox([rx1, min(ry1, ry2), rx2 - rx1, abs(ry2 - ry1)], frameWidth, frameHeight);

end

function [foreheadBox, leftCheekBox, rightCheekBox] = centeredFallbackBoxes(frameWidth, frameHeight)
% Same "no history yet" centered fallback philosophy as
% extractROISignals.m's centered fallback box, sized/positioned to
% roughly match where the three landmark-derived boxes typically land on
% a centered frontal face.
cx = 0.5 * frameWidth;
foreheadBox = clampBox([cx - 0.12 * frameWidth, 0.18 * frameHeight, 0.24 * frameWidth, 0.10 * frameHeight], frameWidth, frameHeight);
leftCheekBox = clampBox([cx - 0.30 * frameWidth, 0.32 * frameHeight, 0.12 * frameWidth, 0.10 * frameHeight], frameWidth, frameHeight);
rightCheekBox = clampBox([cx + 0.18 * frameWidth, 0.32 * frameHeight, 0.12 * frameWidth, 0.10 * frameHeight], frameWidth, frameHeight);
end

function bbox = clampBox(rawBox, frameWidth, frameHeight)
x1 = max(1, round(rawBox(1)));
y1 = max(1, round(rawBox(2)));
x2 = min(frameWidth, round(rawBox(1) + rawBox(3)));
y2 = min(frameHeight, round(rawBox(2) + rawBox(4)));
x2 = max(x2, x1 + 1);
y2 = max(y2, y1 + 1);
bbox = [x1, y1, x2 - x1, y2 - y1];
end

function [redPool, greenPool, bluePool, skippedFilter] = poolSkinPixels(img, boxes)
% Pools RGB pixels from all boxes, concatenate-then-average (matching
% extractROISignals.m's bilateral malar/cheek convention), after a
% classical YCbCr skin-tone gate. If the gate leaves fewer than 10% of a
% box's pixels for ANY box, the gate is skipped for ALL boxes this frame
% (simpler and more conservative than per-box fallback, and avoids mixing
% filtered and unfiltered pixel populations within one frame's average).

redPool = [];
greenPool = [];
bluePool = [];
boxPatches = cell(1, numel(boxes));
skinMasks = cell(1, numel(boxes));
anyTooFew = false;

for k = 1:numel(boxes)
    b = boxes{k};
    x1 = b(1); y1 = b(2); x2 = x1 + b(3); y2 = y1 + b(4);
    patch = img(y1:y2, x1:x2, :);
    boxPatches{k} = patch;
    mask = ycbcrSkinMask(patch);
    skinMasks{k} = mask;
    if nnz(mask) < 0.10 * numel(mask)
        anyTooFew = true;
    end
end

skippedFilter = anyTooFew;

for k = 1:numel(boxes)
    patch = boxPatches{k};
    if skippedFilter
        mask = true(size(patch, 1), size(patch, 2));
    else
        mask = skinMasks{k};
    end
    rC = double(patch(:, :, 1));
    gC = double(patch(:, :, 2));
    bC = double(patch(:, :, 3));
    redPool = [redPool; rC(mask)]; %#ok<AGROW>
    greenPool = [greenPool; gC(mask)]; %#ok<AGROW>
    bluePool = [bluePool; bC(mask)]; %#ok<AGROW>
end

end

function mask = ycbcrSkinMask(patch)
% Classical YCbCr skin-tone threshold (Chai & Ngan 1999-style range),
% applied to a BT.601 YCbCr conversion of the uint8 RGB patch.
% Cb in [77,127], Cr in [133,173] -- a standard, widely-cited range for
% frontal well-lit skin under this color transform.
ycbcr = rgb2ycbcr(patch);
Cb = double(ycbcr(:, :, 2));
Cr = double(ycbcr(:, :, 3));
mask = (Cb >= 77 & Cb <= 127) & (Cr >= 133 & Cr <= 173);
end
