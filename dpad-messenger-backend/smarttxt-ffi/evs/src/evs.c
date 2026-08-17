#include "prot.h"
#include <stdlib.h>
#include <time.h>

void* evs_rs_create() {
    Decoder_State* st = (Decoder_State *)malloc(sizeof(Decoder_State));
    st->cldfbAna = st->cldfbBPF = st->cldfbSyn = NULL;
    st->hFdCngDec = NULL;

    st->writeFECoffset = 0;

    st->codec_mode = 0;
    st->Opt_AMR_WB = 0;
    st->Opt_VOIP = 0;

    st->output_Fs = 32000; // 32hz

    st->bitstreamformat = VOIP_G192_RTP;
    st->amrwb_rfc4867_flag = 0;

    init_decoder(st);
    reset_indices_dec(st);
    srand( (unsigned int) time(0) );

    return (void*)st;
}

void evs_rs_missing(void* decoder, short* out_frame) {
    Decoder_State* st = (Decoder_State *)decoder;
    read_indices_from_djb(st, NULL, 0, 0, 0, 0, 0, 0);
    
    float output[L_FRAME48k];           /* 'float' buffer for output synthesis */
    evs_dec(st, output, FRAMEMODE_MISSING);

    short output_frame = (short)(st->output_Fs / 50);
    syn_output(output, output_frame, out_frame);

    if (st->ini_frame < MAX_FRAME_COUNTER) {
        st->ini_frame++;
    }
}

void evs_rs_decode(void* decoder, unsigned char* frame, size_t frame_len, short* out_frame) {
    Decoder_State* st = (Decoder_State *)decoder;

    if (frame_len == 0) {
        return;
    }

    /* num_bits = frame_len * 8. read_indices_from_djb derives the bitrate from this
     * (total_brate = num_bits * 50), so every EVS primary rate decodes - not just
     * 24.4/16.4/8k. The real sender uses many rates (13.2k = 33 bytes is common), and
     * decoder_selectCodec safely conceals any size that isn't a defined rate. core_mode
     * is only consulted for AMR-WB IO frames, which we never decode, so 0 is fine. */
    read_indices_from_djb(st, frame, frame_len * 8, 0, 0, 1, 0, 0);

    float output[L_FRAME48k];           /* 'float' buffer for output synthesis */
    if( !st->bfi )
    {
        evs_dec( st, output, FRAMEMODE_NORMAL );
    }
    else
    {
        evs_dec( st, output, FRAMEMODE_MISSING );
    }

    short output_frame = (short)(st->output_Fs / 50);
    syn_output( output, output_frame, out_frame );

    if( st->ini_frame < MAX_FRAME_COUNTER )
    {
        st->ini_frame++;
    }
}

void evs_rs_free(void* decoder) {
    destroy_decoder( (Decoder_State *)decoder );
    free(decoder);
}


void* evs_es_create() {
    Encoder_State* st = (Encoder_State *)malloc(sizeof(Encoder_State));

    st->input_Fs = 32000;
    st->total_brate = ACELP_24k40;

    st->Opt_AMR_WB = 0;
    st->Opt_DTX_ON = 0;
    st->Opt_RF_ON = 0;
    st->rf_fec_offset = 0;
    st->rf_fec_indicator = 1;
    st->max_bwidth = SWB;
    st->interval_SID = FIXED_SID_RATE;
    st->var_SID_rate_flag = 1;
    st->Opt_SC_VBR = 0;
    st->last_Opt_SC_VBR =0;
    st->bitstreamformat = VOIP_G192_RTP;

    st->codec_mode = MODE2;
    st->last_codec_mode = st->codec_mode;

    st->ind_list = malloc(sizeof(Indice) * MAX_NUM_INDICES);
    init_encoder( st );
    return (void*)st;
}

short evs_es_encode(void* encoder, short* frame, unsigned char* out) {
    Encoder_State* st = (Encoder_State *)encoder;
    evs_enc( st, frame, 32000 / 50 );

    short pFrame_size = 0;
    indices_to_serial( st, out, &pFrame_size );

    reset_indices_enc(st);
    return (pFrame_size + 7) / 8;
}

void evs_es_free(void* encoder) {
    Encoder_State* st = (Encoder_State *)encoder;
    destroy_encoder(st);
    free(st->ind_list);
    free(st);
}