/*
 * $Id$
 */

package ru.ifmo.cs.bcomp.ui.components;

import java.awt.*;
import javax.swing.JLabel;
import ru.ifmo.cs.bcomp.Utils;
import static ru.ifmo.cs.bcomp.ui.components.DisplayStyles.CELL_HEIGHT;
import static ru.ifmo.cs.bcomp.ui.components.DisplayStyles.COLOR_TITLE;
import ru.ifmo.cs.components.DataDestination;
//import ru.ifmo.cs.components.DataWidth;
import ru.ifmo.cs.components.Register;

/**
 *
 * @author Dmitry Afanasiev <KOT@MATPOCKuH.Ru>
 */
public class RegisterView extends BCompComponent implements DataDestination {
	private int formatWidth;
	private int valuemask;
	private boolean hex;
	private boolean isLeft;

	private final Register reg;
	protected final JLabel value = addValueLabel();

	public RegisterView(Register reg, Color colorTitleBG) {
		super("", 0,colorTitleBG);

		this.reg = reg;
	}

	public RegisterView(Register reg) {
		this(reg, COLOR_TITLE);
	}
	protected void setBounds(int x, int y, int wight){

		setBounds(x,y,this.width=wight,height);

	}

	protected void setProperties(int x, int y, boolean hex, int regWidth,boolean isLeft) {
		this.hex = hex;
		this.formatWidth = regWidth;
		this.valuemask = (1 << regWidth) - 1;
		this.isLeft=isLeft;
		setBounds(x, y, getValueWidth(regWidth, hex)+15);
		setValue();
		if (!isLeft){
		title.setBounds(1,1,25,CELL_HEIGHT+1);
		value.setBounds(27, 1, width-28, CELL_HEIGHT+1);}
		else {title.setBounds(width-26,1,25,CELL_HEIGHT+1);
		value.setBounds(1,1,width-28,CELL_HEIGHT+1);
		}
	}

	public void setProperties(int x, int y, boolean hex,boolean isLeft) {
		setProperties(x, y, hex, (int)reg.width, isLeft);
	}

	protected long getRegWidth(){
		return reg.width;
	}

	protected void setValue(String val) {
		value.setText(val);
	}

	public void setValue() {
		setValue(hex ?
			Utils.toHex(reg.getValue() & valuemask, formatWidth) :
			Utils.toBinary((int)reg.getValue() & valuemask, formatWidth));
	}

	@Override
	public void setValue(long value) {
		setValue();
	}

	public Register getReg() {
		return reg;
	}

	@Override
	public void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.setColor(Color.BLACK);
		g.drawLine(isLeft?width-26:25,1,isLeft?width-26:25,CELL_HEIGHT);

	}
}