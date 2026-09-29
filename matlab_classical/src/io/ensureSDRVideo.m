function outPath = ensureSDRVideo(inPath)
% ENSURESDRVIDEO Normalizes an arbitrary input video so this pipeline's
% VideoReader-based Stage 1 (loadUBFCVideo.m/loadVIPLVideo.m, everything
% downstream) reads correct, standard 8-bit sRGB-ish frames regardless of
% what the source file actually is.
%
% WHY THIS EXISTS (2026-09-29, prompted by "Own Dataset" -- 9 subjects with
% real ground-truth PPG, recorded as phone HDR video): every video this
% project had validated on before (UBFC-rPPG rawvideo/bgr24, VIPL-HR
% mjpeg/yuvj420p) happens to be plain 8-bit SDR with no rotation metadata,
% so VideoReader's frames were always "just correct" -- nobody had to
% think about it. The Own Dataset's phone recordings are NOT that: verified
% via ffprobe, they are HEVC, 10-bit yuv420p10le, color_transfer=
% arib-std-b67 (HLG, an HDR transfer function), and the landscape-named
% file additionally carries a 90-degree rotation Display Matrix that
% MATLAB's VideoReader is confirmed (tested directly, this session) to
% IGNORE -- it decodes without erroring, but hands back sideways,
% non-tone-mapped frames. A Viola-Jones/MediaPipe face detector fed a
% sideways face, or CHROM/POS fed RGB ratios from an uncontrolled HDR->8-bit
% conversion (VideoReader's own Windows Media Foundation backend does SOME
% conversion so it doesn't crash, but it is not a real HLG electro-optical
% transfer function, and its output is unverified for correctness), would
% silently produce wrong results rather than an obvious error -- exactly
% the "any type of video" failure mode this function exists to close off.
%
% METHOD: ffprobe (already on this machine) inspects the video's
% color_transfer, pix_fmt, and rotation side-data. If none of those are
% unusual, this function is a NO-OP -- returns inPath unchanged, so every
% video this project has ever validated on (confirmed via direct ffprobe
% check: UBFC rawvideo/bgr24/color_transfer=unknown, VIPL mjpeg/yuvj420p/
% color_transfer=unknown, neither has a rotation flag) takes this fast
% path and is completely untouched by this function's existence. Only when
% normalization is actually needed does ffmpeg run:
%   - HDR transfer (arib-std-b67/HLG, smpte2084/PQ, smpte428) -> a
%     zscale/tonemap(hable) filter chain (linearize at the source transfer,
%     Hable-tonemap to display-referred, re-encode as standard bt709
%     8-bit yuv420p). ffmpeg's own default -autorotate ALSO applies any
%     rotation Display Matrix in the same pass (verified directly this
%     session: the 90-degree-flagged landscape file produces correctly
%     oriented portrait output through this exact filter chain, with no
%     separate rotation step needed).
%   - Rotation metadata or an unusual (non-8-bit) pix_fmt with NO HDR
%     transfer -> a plain re-encode to yuv420p (still auto-rotated by
%     ffmpeg's own default), no tonemap filter (applying an HLG-linearize
%     step to already-SDR content would be WRONG -- double gamma
%     correction -- so the two cases are deliberately not merged).
% Normalized output is cached (data/processed/_video_cache/, gitignored
% like the rest of data/processed/) keyed by the input's absolute path +
% size + modification time, so a resumable batch script (this project's
% standing convention -- see e.g. run_segment35_mediapipe_anatomy_roi_batch.m)
% never re-runs ffmpeg for a video it already normalized.
%
% GRACEFUL DEGRADATION (matching this project's "never crash the pipeline"
% convention -- see run_spandan_interactive_classical.m's own top-level safety net):
% if ffprobe/ffmpeg are not found on this machine, or ffmpeg exits
% non-zero for any reason, this function prints a clear disp() warning and
% returns inPath UNCHANGED -- exactly today's pre-existing behavior, not a
% new failure mode. It never errors out the caller.
%
% Inputs:
%   inPath  - string/char, full path to a video file (any format
%             VideoReader or ffmpeg can read).
%
% Outputs:
%   outPath - string, either inPath unchanged (the common case, and the
%             ffmpeg-unavailable fallback), or a path to a normalized,
%             cached copy under data/processed/_video_cache/. Callers pass
%             THIS to VideoReader -- they do not need to know which case
%             occurred.
%
% Does NOT modify roi/extractROISignals.m, roi/faceMeshAnatomyROIExtraction.m,
% or any existing cached data/processed/*.mat file -- new, additive,
% called from io/loadUBFCVideo.m and io/loadVIPLVideo.m immediately before
% their own VideoReader(...) call.

outPath = inPath;

if ~isfile(inPath)
    return; % let the caller's own isfile check produce the real error
end

[ffprobeOk, ~] = system('where ffprobe');
[ffmpegOk, ~] = system('where ffmpeg');
if ffprobeOk ~= 0 || ffmpegOk ~= 0
    disp('ensureSDRVideo: ffprobe/ffmpeg not found on this machine -- skipping video normalization (using the file as-is, today''s pre-existing behavior).');
    return;
end

probeCmd = sprintf('ffprobe -v error -select_streams v:0 -show_entries stream=pix_fmt,color_transfer:stream_side_data=rotation -of json "%s"', inPath);
[probeStatus, probeOut] = system(probeCmd);

if probeStatus ~= 0
    disp(['ensureSDRVideo: ffprobe failed on ' inPath ' -- skipping normalization (using the file as-is).']);
    return;
end

try
    probeInfo = jsondecode(probeOut);
    streamInfo = probeInfo.streams(1);
catch
    disp(['ensureSDRVideo: could not parse ffprobe output for ' inPath ' -- skipping normalization (using the file as-is).']);
    return;
end

pixFmt = '';
if isfield(streamInfo, 'pix_fmt')
    pixFmt = streamInfo.pix_fmt;
end

colorTransfer = '';
if isfield(streamInfo, 'color_transfer')
    colorTransfer = streamInfo.color_transfer;
end

hdrTransferList = {'arib-std-b67', 'smpte2084', 'smpte428'};
isHDR = any(strcmpi(colorTransfer, hdrTransferList));

hasUnusualPixFmt = contains(pixFmt, '10le') || contains(pixFmt, '12le') || contains(pixFmt, 'p010') || contains(pixFmt, 'p012');

hasRotation = false;
if isfield(streamInfo, 'side_data_list')
    sideDataList = streamInfo.side_data_list;
    if ~iscell(sideDataList)
        sideDataList = {sideDataList};
    end
    for k = 1:numel(sideDataList)
        sd = sideDataList{k};
        if isstruct(sd) && isfield(sd, 'rotation') && sd.rotation ~= 0
            hasRotation = true;
        end
    end
end

needsNormalization = isHDR || hasUnusualPixFmt || hasRotation;

if ~needsNormalization
    return; % fast path -- every video this project has validated on lands here, unchanged
end

disp(['ensureSDRVideo: ' inPath ' needs normalization (HDR transfer=' colorTransfer ...
    ', pix_fmt=' pixFmt ', rotation flag=' num2str(hasRotation) ') -- tone-mapping/re-encoding via ffmpeg.']);

thisFileDir = fileparts(mfilename('fullpath'));
projectRoot = fileparts(fileparts(fileparts(thisFileDir)));
cacheDir = fullfile(projectRoot, 'data', 'processed', '_video_cache');
if ~isfolder(cacheDir)
    mkdir(cacheDir);
end

fileInfo = dir(inPath);
cacheKeyString = sprintf('%s|%d|%f', inPath, fileInfo.bytes, fileInfo.datenum);
md = java.security.MessageDigest.getInstance('MD5');
hashBytes = typecast(md.digest(uint8(cacheKeyString)), 'uint8');
hashHex = lower(sprintf('%02x', hashBytes));

cachedPath = fullfile(cacheDir, ['sdr_' hashHex '.mp4']);

if isfile(cachedPath)
    outPath = cachedPath;
    return; % already normalized this exact file (by content+mtime) -- resumable, no re-encode
end

if isHDR
    filterChain = 'zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=full,format=yuv420p';
else
    filterChain = 'format=yuv420p'; % rotation-only/odd-pix_fmt case: no HLG linearize step
end

tempOutPath = [cachedPath '.tmp.mp4'];
encodeCmd = sprintf('ffmpeg -y -i "%s" -vf "%s" -c:v libx264 -preset veryfast -crf 18 -an "%s"', inPath, filterChain, tempOutPath);
[encodeStatus, encodeOut] = system(encodeCmd);

if encodeStatus ~= 0 || ~isfile(tempOutPath)
    disp(['ensureSDRVideo: ffmpeg normalization FAILED for ' inPath ' -- using the original file as-is. ffmpeg output: ' encodeOut(max(1, end - 500):end)]);
    if isfile(tempOutPath)
        delete(tempOutPath);
    end
    return;
end

movefile(tempOutPath, cachedPath);
outPath = cachedPath;
disp(['ensureSDRVideo: normalized copy cached at ' cachedPath]);

end
